package com.downloadsource.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TsSegment(val url: String, val key: ByteArray?, val iv: ByteArray?, val range: String? = null, val init: ByteArray? = null, val durSec: Double = 0.0)

class GlobalRateLimiter(private val permitsPerSecond: Long) {
    private var nextFreeTicketMicros = 0L
    private val stableIntervalMicros = if (permitsPerSecond > 0) 1_000_000L / permitsPerSecond else 0L
    @Synchronized fun acquire(requiredPermits: Long) {
        if (stableIntervalMicros == 0L) return
        val nowMicros = System.nanoTime() / 1000
        val waitMicros = maxOf(0L, nextFreeTicketMicros - nowMicros)
        if (waitMicros > 0) try { Thread.sleep(waitMicros / 1000) } catch (e: Exception) {}
        nextFreeTicketMicros = maxOf(nextFreeTicketMicros, nowMicros) + requiredPermits * stableIntervalMicros
    }
}

class Segmenter(private val client: OkHttpClient) {
    /**
     * 分片专用客户端：强制 HTTP/1.1。
     * 多数 CDN 在 HTTP/2 下把 40+ 个 Range 请求复用同一条 TCP，
     * 会受单连接窗口/流控限制，实测只有几十 KB/s；
     * 改成一请求一连接后每路都能吃满自己的带宽，这是「下载慢」最常见的原因。
     */
    private val fastClient = client.newBuilder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()
    private val activeCalls = ConcurrentHashMap<String, MutableList<okhttp3.Call>>()

    fun cancelTask(taskId: String) {
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    suspend fun download(taskId: String, url: String, outFile: File, parts: Int, speedLimitKb: Long, customHeaders: Map<String, String>, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val headers = okhttp3.Headers.Builder().apply { customHeaders.forEach { (k, v) -> add(k, v) } }.build()
        
        if (url.contains(".m3u8", true)) return@coroutineScope downloadM3u8(taskId, url, outFile, parts, headers, onTick)
        if (url.contains(".mpd", true)) return@coroutineScope downloadDash(taskId, url, outFile, parts, headers, onTick)

        // 核心修复：智能探测文件大小与 Range 支持
        val (total, supportsRange) = probeFileSize(url, headers)
        
        // 如果探测不到大小，直接降级为流式下载，绝不报错
        if (total <= 0) {
            return@coroutineScope streamDownload(taskId, url, outFile, headers, onTick)
        }
        
        // 32 路并发抓取；按 1MB/片下限收敛，避免碎成几万个小块
        val maxBySize = (total / (512L shl 10)).toInt().coerceAtLeast(1)
        // 不限速时大文件自动加路数（8MB 以上至少 24 路），小文件保持轻量
        val autoParts = when {
            total > 1024L shl 20 -> 64
            total > 256L shl 20 -> 56
            total > 128L shl 20 -> 48
            total > 64L shl 20 -> 32
            total > 8L shl 20 -> maxOf(parts, 24)
            else -> parts
        }
        var n = if (supportsRange && total > 2L shl 20) autoParts.coerceIn(1, 64).coerceAtMost(maxBySize) else 1
        // 独家：并发标定——先用 4 路小 Range 请求实测真实带宽，再决定分片数
        if (supportsRange && total > 8L shl 20 && speedLimitKb <= 0) {
            n = calibrateParts(url, headers, total, n).coerceIn(1, 64).coerceAtMost(maxBySize)
        }
        // 独家：连接预热——并发建连，把 TCP/TLS 握手开销挪到下载开始前，首字节更快、抖动更小
        if (n > 1 && supportsRange) {
            withTimeoutOrNull(2000) { runCatching { prewarm(url, headers, n) } }
        }
        val downloaded = AtomicLong(0)
        val rateLimiter = GlobalRateLimiter(if (speedLimitKb > 0) speedLimitKb * 1024 else 0)

        val counters = List(n) { AtomicLong(0) }
        var lastTick = System.currentTimeMillis(); var lastBytes = 0L
        var smoothSpeed = 0.0
        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis(); val cur = counters.sumOf { it.get() }
                val raw = if (now > lastTick) (cur - lastBytes) * 1000 / (now - lastTick) else 0L
                smoothSpeed = if (smoothSpeed <= 0.0) raw.toDouble() else smoothSpeed * 0.7 + raw * 0.3
                val speed = smoothSpeed.toLong()
                lastTick = now; lastBytes = cur
                onTick(cur, total, speed, List(n) { i -> val cs = i * total / n; val ce = if (i == n - 1) total else (i + 1) * total / n; (counters[i].get().toFloat() / (ce - cs).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) })
            }
        }

        try {
            RandomAccessFile(outFile, "rw").setLength(total)
            (0 until n).map { i ->
                async(Dispatchers.IO) {
                    val start = i * total / n; val end = if (i == n - 1) total - 1 else (i + 1) * total / n - 1
                    retryDownloadRange(taskId, url, start, end, outFile, counters[i], rateLimiter, headers, n == 1)
                }
            }.awaitAll()
            ticker.cancel()
            onTick(total, total, 0, List(n) { 1f })
            outFile
        } catch (e: Exception) {
            ticker.cancel()
            val msg = e.message ?: ""
            // 服务端不支持 Range（返回 200）或区间校验失败时，自动降级单流重下，不让任务废掉
            if (msg.contains("200") || msg.contains("区间")) {
                runCatching { outFile.delete() }
                return@coroutineScope streamDownload(taskId, url, outFile, headers, onTick)
            }
            if (!outFile.delete()) outFile.deleteOnExit()
            throw e
        }
    }

    // 三重探测机制：HEAD -> GET 0-0 -> 放弃探测直接流式
    private suspend fun probeFileSize(url: String, headers: okhttp3.Headers): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        // 1. 尝试 HEAD
        try {
            val headReq = Request.Builder().url(url).headers(headers).header("Accept-Encoding", "identity").head().build()
            fastClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val range = resp.header("Accept-Ranges")?.contains("bytes", true) == true
                    // 很多 CDN 不在 HEAD 里声明 Accept-Ranges，但实际支持；len 已知时用 0-0 验证
                    if (len > 0 && range) return@withContext Pair(len, true)
                    if (len > 0) {
                        val verify = runCatching {
                            fastClient.newCall(
                                Request.Builder().url(url).headers(headers)
                                    .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
                            ).execute().use { r -> r.code == 206 }
                        }.getOrDefault(false)
                        return@withContext Pair(len, verify)
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. 尝试 GET Range: 0-0
        try {
            val rangeReq = Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
            fastClient.newCall(rangeReq).execute().use { resp ->
                if (resp.code == 206) {
                    val contentRange = resp.header("Content-Range")
                    val len = contentRange?.substringAfter("/")?.toLongOrNull() ?: -1L
                    if (len > 0) return@withContext Pair(len, true)
                } else if (resp.isSuccessful) {
                    // 如果服务器强行返回 200，说明不支持 Range，但可能拿到了长度
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (len > 0) return@withContext Pair(len, false)
                }
            }
        } catch (e: Exception) {}

        return@withContext Pair(-1L, false)
    }

    // 修复：没有大小信息时的流式下载
    private suspend fun streamDownload(taskId: String, url: String, outFile: File, headers: okhttp3.Headers, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val partFile = File(outFile.parentFile, ".${outFile.name}.part")
        val already = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder().url(url).headers(headers).header("Accept-Encoding", "identity")
        if (already > 0) reqBuilder.header("Range", "bytes=$already-")
        val call = fastClient.newCall(reqBuilder.build())
        activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)

        try {
            withContext(Dispatchers.IO) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 416) error("HTTP ${resp.code}")
                    if (resp.code == 416 && already > 0) {
                        // 服务器认为已经下完，直接收尾
                        val t = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: already
                        onTick(t, t, 0L, emptyList())
                    } else {
                    // 200 表示服务端忽略 Range，必须从头覆盖写
                    val appendMode = already > 0 && resp.code == 206
                    val baseOffset = if (appendMode) already else 0L
                    val total = resp.header("Content-Length")?.toLongOrNull()?.let { if (appendMode) it + already else it } ?: -1L
                    resp.body?.byteStream()?.use { ins ->
                        java.io.FileOutputStream(partFile, appendMode).use { out ->
                            val buf = ByteArray(4 * 1024 * 1024)
                            var downloaded = baseOffset
                            var lastTick = System.currentTimeMillis()
                            while (true) {
                                if (!isActive) break
                                val r = ins.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                                downloaded += r
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 300) {
                                    onTick(downloaded, if (total > 0) total else 0L, 0L, emptyList())
                                    lastTick = now
                                }
                            }
                            out.fd.sync()
                        }
                    }
                    }
                }
            }
            if (!partFile.renameTo(outFile)) { partFile.copyTo(outFile, true); partFile.delete() }
            onTick(outFile.length(), outFile.length(), 0L, emptyList())
            outFile
        } finally {
            activeCalls[taskId]?.remove(call)
        }
    }

    /**
     * 并发标定（独家）：同时发 4 个 192KB 的 Range 请求，测聚合吞吐。
     * 线路差（<500KB/s）就拉满 64 路抢带宽；线路好（>12MB/s）反而降到 32 路减少握手与调度开销。
     * 任何异常一律回退默认分片数，绝不影响下载。
     */
    private suspend fun calibrateParts(url: String, headers: okhttp3.Headers, total: Long, expect: Int): Int =
        withContext(Dispatchers.IO) {
            runCatching<Int> {
                val sample = 192L * 1024
                if (total < sample * 8) return@runCatching expect
                val workers = 4
                val t0 = System.currentTimeMillis()
                val got = (0 until workers).map { i ->
                    async {
                        val start = total / workers * i
                        val end = (start + sample - 1).coerceAtMost(total - 1)
                        val req = okhttp3.Request.Builder().url(url).headers(headers)
                            .header("Range", "bytes=$start-$end").build()
                        runCatching {
                            fastClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@use 0L
                                val ins = resp.body?.byteStream() ?: return@use 0L
                                val buf = ByteArray(32 * 1024)
                                var read = 0L
                                while (read < sample) {
                                    val r = ins.read(buf)
                                    if (r <= 0) break
                                    read += r
                                }
                                read
                            }
                        }.getOrDefault(0L)
                    }
                }.awaitAll().sum()
                val ms = (System.currentTimeMillis() - t0).coerceAtLeast(50)
                val bps = got * 1000.0 / ms
                when {
                    bps < 500_000 -> 64
                    bps < 2_000_000 -> maxOf(expect, 48)
                    bps > 12_000_000 -> minOf(expect, 32)
                    else -> expect
                }
            }.getOrDefault(expect)
        }

    /** 并发建连预热：最多 16 条连接，失败静默 */
    private suspend fun prewarm(url: String, headers: okhttp3.Headers, n: Int) = coroutineScope {
        (0 until n.coerceIn(1, 16)).map {
            async(Dispatchers.IO) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(url).headers(headers)
                        .header("Range", "bytes=0-0").build()
                    fastClient.newCall(req).execute().use { resp ->
                        resp.body?.byteStream()?.read(ByteArray(1))
                    }
                }
            }
        }.awaitAll()
    }

    /** 跳过 n 字节但不受 BufferedInputStream 大跳影响 */
    private fun skipFully(ins: java.io.InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(64 * 1024)
        while (left > 0) {
            val r = ins.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r <= 0) throw Exception("跳过偏移失败，剩余 $left 字节")
            left -= r
        }
    }

    private suspend fun retryDownloadRange(taskId: String, url: String, start: Long, end: Long, file: File, counter: AtomicLong, rateLimiter: GlobalRateLimiter, headers: okhttp3.Headers, allow200: Boolean) {
        var attempt = 0
        while (attempt < 4) {
            if (!currentCoroutineContext().isActive) return
            // 每次重试都从本片起点整段重下，计数归零，避免进度累加虚高
            counter.set(0)
            try {
                downloadRange(taskId, url, start, end, file, counter, rateLimiter, headers, allow200)
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                attempt++
                if (attempt >= 4) throw e
                delay(minOf(10_000L, 800L * (1L shl attempt)))
            }
        }
    }

    private suspend fun downloadRange(taskId: String, url: String, start: Long, end: Long, file: File, downloaded: AtomicLong, rateLimiter: GlobalRateLimiter, headers: okhttp3.Headers, allow200: Boolean) {
        // 必须显式声明 identity：否则服务端 gzip 会打乱字节偏移
        val req = Request.Builder().url(url).headers(headers)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .build()
        val call = fastClient.newCall(req)
        activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)
        
        try {
            call.execute().use { resp ->
                if (resp.code != 206 && !(allow200 && resp.code == 200)) {
                    throw Exception("分片未按区间返回：HTTP ${resp.code}")
                }
                // 校验服务端返回的起点，防止错位写入
                if (resp.code == 206) {
                    val crStart = resp.header("Content-Range")?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull()
                    if (crStart != null && crStart != start) throw Exception("区间起点不一致：请求 $start，返回 $crStart")
                }
                val want = end - start + 1
                val fullBody = resp.code == 200
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(start)
                    resp.body?.byteStream()?.use { ins ->
                        // 服务端忽略 Range 返回整文件时，必须先跳过 start 字节，否则分片会互相覆盖
                        if (fullBody && start > 0) skipFully(ins, start)
                        val buf = ByteArray(4 * 1024 * 1024)
                        var written = 0L
                        while (written < want) {
                            if (!currentCoroutineContext().isActive) break
                            val r = ins.read(buf, 0, minOf(buf.size.toLong(), want - written).toInt())
                            if (r <= 0) break
                            raf.write(buf, 0, r)
                            written += r
                            downloaded.addAndGet(r.toLong())
                            rateLimiter.acquire(r.toLong())
                        }
                        // 关键校验：区间没下满就是残缺（以前会生成「大小对但只有片头」的文件）
                        if (written < want) throw Exception("分片不完整：$written/$want 字节")
                    }
                }
            }
        } finally {
            activeCalls[taskId]?.remove(call)
        }
    }

    private suspend fun downloadM3u8(taskId: String, url: String, outFile: File, parts: Int, headers: okhttp3.Headers, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val finalUrl = url
        // 直播流（无 ENDLIST）：按目标时长持续追帧，直到结束或用户停止
        val probe = runCatching { fetchText(finalUrl, headers) }.getOrDefault("")
        if (probe.contains("#EXTINF") && !probe.contains("#EXT-X-ENDLIST")) {
            return@coroutineScope downloadLiveHls(taskId, finalUrl, outFile, parts, headers, onTick)
        }
        val segments = parseM3u8(finalUrl, mutableSetOf(), headers)
        if (segments.isEmpty()) error("未找到有效的 TS 分片 (´･_･`)")
        
        val tmpDir = File(outFile.parentFile, ".${outFile.name}.ts_parts").apply { mkdirs() }
        // 跨次续传校验：清单指纹（首尾分片 + 数量）变化时丢弃旧分片，避免拼错
        val fingerprint = "${segments.size}|${segments.firstOrNull()?.url}|${segments.lastOrNull()?.url}"
        val metaFile = File(tmpDir, ".fingerprint")
        val oldFp = runCatching { metaFile.readText() }.getOrNull()
        if (oldFp != null && oldFp != fingerprint) tmpDir.listFiles()?.forEach { it.delete() }
        runCatching { metaFile.writeText(fingerprint) }
        val downloaded = AtomicLong(segments.count { File(tmpDir, "$it.ts").let { f -> f.exists() && f.length() > 0 } }.toLong())
        val total = segments.size.toLong()
        val ticker = launch(Dispatchers.Default) { while (isActive) { delay(300); onTick(downloaded.get(), total, 0, emptyList()) } }

        val semaphore = Semaphore(parts)
        segments.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val partFile = File(tmpDir, "$index.ts")
                    if (segment.init != null) { partFile.writeBytes(segment.init); downloaded.incrementAndGet(); return@withPermit }
                    if (!partFile.exists() || partFile.length() == 0L) {
                        var r = 3
                        while (r > 0) {
                            if (!isActive) return@withPermit
                            var callRef: okhttp3.Call? = null
                            try {
                                val rb = Request.Builder().url(segment.url).headers(headers)
                                segment.range?.let { r -> rb.header("Range", "bytes=$r") }
                                val call = fastClient.newCall(rb.build())
                                callRef = call
                                activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)
                                call.execute().use { resp ->
                                    if (!resp.isSuccessful) throw Exception("分片 HTTP ${resp.code}")
                                    var bytes = resp.body?.bytes() ?: ByteArray(0)
                                    if (segment.key != null && segment.iv != null) bytes = decryptAES(bytes, segment.key, segment.iv)
                                    partFile.writeBytes(bytes)
                                }
                                break
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                r--; if (r == 0) throw e; delay(1000)
                            } finally {
                                callRef?.let { activeCalls[taskId]?.remove(it) }
                            }
                        }
                    }
                    downloaded.incrementAndGet()
                }
            }
        }.awaitAll()
        ticker.cancel()

        val partFiles = segments.indices.map { File(tmpDir, "$it.ts") }
        val expectedSec = segments.sumOf { it.durSec }
        val mergedTs = File(outFile.parentFile, "${outFile.name}.tmp_merged.ts")
        var success = withContext(Dispatchers.IO) {
            runCatching { remuxSegmentsToMp4(partFiles, outFile) }.getOrDefault(false) && mp4CoverageOk(outFile, expectedSec)
        }
        if (!success) {
            // 第二方案：先拼完整 TS，再整体重封装为 MP4（避免逐段封装丢音画）
            runCatching { mergedTs.delete() }
            val merged = runCatching {
                ZeroCore.mergeFiles(partFiles.map { it.absolutePath }.toTypedArray(), mergedTs.absolutePath)
            }.getOrDefault(false)
            if (merged) {
                success = withContext(Dispatchers.IO) {
                    runCatching { remuxTsToMp4(mergedTs, outFile) }.getOrDefault(false) && mp4CoverageOk(outFile, expectedSec)
                }
            }
        }
        onTick(total, total, 0, emptyList())
        if (!success) {
            // 最后兜底：交付完整 TS（内容完整，只是封装不是 MP4），绝不给半截文件
            if (!mergedTs.exists() || mergedTs.length() == 0L) {
                runCatching { ZeroCore.mergeFiles(partFiles.map { it.absolutePath }.toTypedArray(), mergedTs.absolutePath) }
            }
            val fallbackTs = File(outFile.parentFile, outFile.name.substringBeforeLast('.') + ".ts")
            runCatching { outFile.delete() }
            if (mergedTs.exists() && mergedTs.length() > 0L) {
                runCatching { mergedTs.renameTo(fallbackTs); fallbackTs.copyTo(File(outFile.parentFile, outFile.name), true); fallbackTs.delete() }
            }
            if (fallbackTs.exists() && fallbackTs.length() > 0L) return@coroutineScope fallbackTs
            if (outFile.exists() && outFile.length() > 0L) return@coroutineScope outFile
            throw Exception("TS 合并失败 (´･_･`)")
        }
        mergedTs.delete()
        runCatching { tmpDir.deleteRecursively() }
        outFile
    }

    private suspend fun fetchText(url: String, headers: okhttp3.Headers): String = withContext(Dispatchers.IO) {
        fastClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { it.body?.string() ?: "" }
    }

    /** 直播 HLS：滚动刷新播放列表，增量追帧并落盘，结束后合成 mp4 */
    private suspend fun downloadLiveHls(taskId: String, url: String, outFile: File, parts: Int, headers: okhttp3.Headers, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val tmpDir = File(outFile.parentFile, ".${outFile.name}.ts_parts").apply { mkdirs() }
        val seen = LinkedHashMap<String, Int>()
        val downloaded = AtomicLong(0)
        val semaphore = Semaphore(parts)
        val ticker = launch(Dispatchers.Default) {
            while (isActive) { delay(500); onTick(downloaded.get(), seen.size.toLong().coerceAtLeast(1), 0, emptyList()) }
        }
        var ended = false
        var cycles = 0
        try {
            while (currentCoroutineContext().isActive && cycles < 240 && !ended) {
                cycles++
                val text = runCatching { fetchText(url, headers) }.getOrDefault("")
                if (text.contains("#EXT-X-ENDLIST")) ended = true
                val segs = runCatching { parseM3u8(url, mutableSetOf(), headers) }.getOrDefault(emptyList())
                val batch = mutableListOf<Deferred<Unit>>()
                segs.forEach { seg ->
                    if (seen.containsKey(seg.url)) return@forEach
                    if (seen.size >= 4000) return@forEach
                    val idx = seen.size
                    seen[seg.url] = idx
                    val part = File(tmpDir, "$idx.ts")
                    if (part.exists() && part.length() > 0L) { downloaded.incrementAndGet(); return@forEach }
                    batch.add(async(Dispatchers.IO) {
                        semaphore.withPermit {
                            var attempt = 0
                            while (attempt < 3) {
                                try {
                                    val rb = Request.Builder().url(seg.url).headers(headers)
                                    seg.range?.let { rb.header("Range", "bytes=$it") }
                                    fastClient.newCall(rb.build()).execute().use { r ->
                                        if (!r.isSuccessful) error("分片 HTTP ${r.code}")
                                        var bytes = r.body?.bytes() ?: ByteArray(0)
                                        if (seg.key != null && seg.iv != null) bytes = decryptAES(bytes, seg.key, seg.iv)
                                        part.writeBytes(bytes)
                                    }
                                    downloaded.incrementAndGet()
                                    break
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    attempt++
                                    if (attempt >= 3) throw e
                                    delay(800L * attempt)
                                }
                            }
                        }
                    })
                }
                batch.forEach { runCatching { it.await() } }
                if (ended) break
                val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
                delay(target.coerceIn(2L, 30L) * 1000L)
            }
        } finally { ticker.cancel() }

        val mergedTs = File(outFile.parentFile, "${outFile.name}.tmp_merged.ts")
        val files = (0 until seen.size).map { File(tmpDir, "$it.ts") }.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) error("未追上任何直播分片 (´･_･`)")
        if (!ZeroCore.mergeFiles(files.map { it.absolutePath }.toTypedArray(), mergedTs.absolutePath)) error("直播分片合并失败")
        val ok = withContext(Dispatchers.IO) { runCatching { remuxTsToMp4(mergedTs, outFile); true }.getOrDefault(false) }
        onTick(downloaded.get(), downloaded.get().coerceAtLeast(1), 0, emptyList())
        if (!ok) {
            val ts = File(outFile.parentFile, outFile.name.substringBeforeLast('.') + ".ts")
            if (mergedTs.renameTo(ts)) return@coroutineScope ts
            throw Exception("直播合成失败 (´･_･`)")
        }
        tmpDir.deleteRecursively()
        outFile
    }

    private suspend fun parseM3u8(url: String, visited: MutableSet<String>, headers: okhttp3.Headers): List<TsSegment> = withContext(Dispatchers.IO) {
        if (visited.contains(url)) return@withContext emptyList()
        visited.add(url)
        val text = runCatching {
            fastClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { it.body?.string() ?: "" }
        }.getOrDefault("")
        if (text.isBlank()) return@withContext emptyList()
        val lines = text.lines()

        // 主播放列表：按带宽选最高，而不是抓第一条（第一条常是最低清）
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            var bestUrl = ""; var bestBandwidth = -1L
            var pendingBandwidth = -1L
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                } else if (t.isNotBlank() && !t.startsWith("#")) {
                    if (pendingBandwidth >= bestBandwidth) { bestBandwidth = pendingBandwidth; bestUrl = t }
                    pendingBandwidth = -1L
                }
            }
            if (bestUrl.isNotBlank()) return@withContext parseM3u8(resolveUrl(url, bestUrl), visited, headers)
        }

        var currentKey: ByteArray? = null; var currentIv: ByteArray? = null
        var mediaSequence = 0L; var segmentIndex = 0L
        var mapUri: String? = null
        var pendingRange: String? = null
        var pendingDur = 0.0
        val keyCache = HashMap<String, ByteArray>()
        val result = mutableListOf<TsSegment>()

        for (raw in lines) {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(trimmed)?.groupValues?.get(1)?.trim() ?: "NONE"
                    if (method == "AES-128") {
                        val keyUri = Regex("URI=\"(.*?)\"") .find(trimmed)?.groupValues?.get(1)
                        val ivStr = Regex("IV=0x([0-9A-Fa-f]+)").find(trimmed)?.groupValues?.get(1)
                        if (keyUri != null) {
                            val absKey = resolveUrl(url, keyUri)
                            currentKey = keyCache.getOrPut(absKey) {
                                runCatching {
                                    fastClient.newCall(Request.Builder().url(absKey).headers(headers).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
                                }.getOrDefault(ByteArray(0))
                            }
                            currentIv = if (ivStr != null) hexStringToByteArray(ivStr) else null
                        } else { currentKey = null; currentIv = null }
                    } else { currentKey = null; currentIv = null }
                }
                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
                trimmed.startsWith("#EXT-X-MAP:") ->
                    Regex("URI=\"(.*?)\"") .find(trimmed)?.groupValues?.get(1)?.let { mapUri = resolveUrl(url, it) }
                trimmed.startsWith("#EXTINF:") ->
                    pendingDur = trimmed.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: 0.0
                trimmed.startsWith("#EXT-X-BYTERANGE:") ->
                    pendingRange = trimmed.substringAfter(":").trim()
                trimmed.isNotBlank() && !trimmed.startsWith("#") -> {
                    val absUrl = resolveUrl(url, trimmed)
                    if (absUrl.contains(".m3u8", ignoreCase = true)) {
                        result.addAll(parseM3u8(absUrl, visited, headers))
                    } else {
                        val ivToUse = currentIv ?: if (currentKey != null) {
                            val seq = mediaSequence + segmentIndex
                            ByteArray(16).apply {
                                this[11] = (seq shr 32).toByte(); this[12] = (seq shr 24).toByte()
                                this[13] = (seq shr 16).toByte(); this[14] = (seq shr 8).toByte(); this[15] = seq.toByte()
                            }
                        } else null
                        result.add(TsSegment(absUrl, currentKey, ivToUse, pendingRange, null, pendingDur))
                        pendingRange = null; pendingDur = 0.0
                    }
                    segmentIndex++
                }
            }
        }
        // fMP4 初始化段（EXT-X-MAP）放在最前，否则合并后无法播放
        if (mapUri != null) {
            runCatching {
                fastClient.newCall(Request.Builder().url(mapUri!!).headers(headers).build()).execute().use { resp ->
                    resp.body?.bytes()?.let { bytes ->
                        result.add(0, TsSegment(mapUri!!, null, null, null, bytes))
                    }
                }
            }
        }
        return@withContext result
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return java.net.URI(baseUrl).resolve(relativeUrl).toString()
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length; val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        return data
    }

    private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) { data }
    }

    // ---------- DASH (MPD) ----------

    private data class DashTrack(val kind: String, val initUrl: String?, val segmentUrls: List<String>, val bandwidth: Long)

    private suspend fun downloadDash(taskId: String, url: String, outFile: File, parts: Int, headers: okhttp3.Headers, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val xml = withContext(Dispatchers.IO) {
            fastClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { it.body?.string() ?: "" }
        }
        if (xml.isBlank() || !xml.contains("<MPD")) error("MPD 拉取失败 (´･_･`)")

        val durSec = Regex("mediaPresentationDuration=\"PT([0-9.]+)S\"").find(xml)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("<Period[^>]*duration=\"PT([0-9.]+)S\"").find(xml)?.groupValues?.get(1)?.toDoubleOrNull()

        val tracks = parseDashTracks(xml, url, durSec)
        if (tracks.isEmpty()) error("不支持的 MPD：未找到可用分片模板")

        var downloadedBytes = AtomicLong(0)
        val totalSegments = tracks.sumOf { it.segmentUrls.size }.coerceAtLeast(1).toLong()
        val doneSegments = AtomicLong(0)
        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(500)
                onTick(downloadedBytes.get(), 0L, 0L, emptyList())
            }
        }

        val tmpRoot = File(outFile.parentFile, ".${outFile.name}.dash_parts").apply { mkdirs() }
        val outputs = mutableListOf<File>()
        try {
            tracks.forEachIndexed { ti, track ->
                val dir = File(tmpRoot, "t$ti").apply { mkdirs() }
                val semaphore = Semaphore(parts)
                var initPath: String? = null
                if (track.initUrl != null) {
                    val f = File(dir, "init.mp4")
                    if (!f.exists() || f.length() == 0L) {
                        fastClient.newCall(Request.Builder().url(track.initUrl).headers(headers).build()).execute().use { r ->
                            if (r.isSuccessful) f.writeBytes(r.body?.bytes() ?: ByteArray(0)) else error("init 分片 HTTP ${r.code}")
                        }
                    }
                    initPath = f.absolutePath
                }
                track.segmentUrls.mapIndexed { idx, segUrl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val part = File(dir, "$idx.m4s")
                            if (!part.exists() || part.length() == 0L) {
                                var attempt = 0
                                while (attempt < 4) {
                                    try {
                                        fastClient.newCall(Request.Builder().url(segUrl).headers(headers).build()).execute().use { r ->
                                            if (!r.isSuccessful) error("分片 HTTP ${r.code}")
                                            val bytes = r.body?.bytes() ?: ByteArray(0)
                                            part.writeBytes(bytes)
                                            downloadedBytes.addAndGet(bytes.size.toLong())
                                        }
                                        break
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        attempt++
                                        if (attempt >= 4) throw e
                                        delay(700L * (1L shl attempt))
                                    }
                                }
                            } else downloadedBytes.addAndGet(part.length())
                            doneSegments.incrementAndGet()
                        }
                    }
                }.awaitAll()

                val list = mutableListOf<String>()
                initPath?.let { list.add(it) }
                // SegmentList 这类可能不连续编号，按落盘顺序合并
                (0 until track.segmentUrls.size).forEach { i -> File(dir, "$i.m4s").takeIf { it.exists() }?.let { list.add(it.absolutePath) } }
                val merged = File(tmpRoot, "track$ti.${if (track.kind == "audio") "m4a" else "mp4"}")
                if (list.size <= 1) {
                    if (list.isNotEmpty()) File(list.first()).copyTo(merged, true) else error("分片为空")
                } else if (!ZeroCore.mergeFiles(list.toTypedArray(), merged.absolutePath)) error("分片合并失败")
                outputs.add(merged)
            }
        } finally { /* 保留分片便于续传，成功后统一清理 */ }

        ticker.cancel()
        val finalFile = if (outputs.size >= 2) {
            val muxed = File(tmpRoot, "muxed.mp4")
            if (runCatching { remuxToMp4(outputs, muxed) }.isSuccess && muxed.exists() && muxed.length() > 0) {
                muxed.copyTo(outFile, true); muxed
            } else {
                // 音频轨合成失败时先把视频轨交付，至少能看
                outputs.first().copyTo(outFile, true); outputs.first()
            }
        } else {
            outputs.first().copyTo(outFile, true); outputs.first()
        }
        tmpRoot.deleteRecursively()
        onTick(finalFile.length(), finalFile.length(), 0, List(tracks.size.coerceAtLeast(1)) { 1f })
        return@coroutineScope outFile
    }

    private fun parseDashTracks(xml: String, mpdUrl: String, durSec: Double?): List<DashTrack> {
        val adaptations = Regex("<AdaptationSet([^>]*)>(.*?)</AdaptationSet>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).map { it.value }.toList()
        val tracks = mutableListOf<DashTrack>()

        data class Cand(val kind: String, val bandwidth: Long, val init: String?, val segs: List<String>, val base: String)

        for (block in adaptations) {
            val headAttrs = Regex("<AdaptationSet([^>]*)>").find(block)?.groupValues?.get(1) ?: ""
            val content = block.substringAfter('>').substringBeforeLast("</AdaptationSet>")
            val repBlocks = Regex("<Representation([^>]*?)(/>|>(.*?)</Representation>)", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(block).toList()
            if (repBlocks.isEmpty()) continue

            val best = repBlocks.map { m ->
                val attrs = m.groupValues[1]
                val body = m.groupValues[3]
                val mime = attrOf(attrs, "mimeType") ?: attrOf(headAttrs, "mimeType") ?: ""
                val kind = when {
                    mime.contains("audio") || attrOf(headAttrs, "contentType") == "audio" -> "audio"
                    mime.contains("video") -> "video"
                    else -> ""
                }
                if (kind.isEmpty()) return@map null
                val bandwidth = attrOf(attrs, "bandwidth")?.toLongOrNull() ?: 0L
                val repId = attrOf(attrs, "id") ?: ""
                val baseUrl = Regex("<BaseURL>(.*?)</BaseURL>").find(body)?.groupValues?.get(1)?.trim() ?: ""
                val template = Regex("<SegmentTemplate([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL)).find(body)?.groupValues?.get(1)
                    ?: Regex("<SegmentTemplate([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL)).find(headAttrs)?.groupValues?.get(1)
                val segList = Regex("<SegmentURL([^>]*?)/?>").findAll(body).mapNotNull { attrOf(it.groupValues[1], "media") }.toList()
                var init: String? = null
                var segs: List<String> = emptyList()
                if (!segList.isEmpty()) {
                    init = null
                    segs = segList.map { resolveUrl(mpdUrl, joinBase(baseUrl, it)) }
                } else if (template != null) {
                    val media = attrOf(template, "media") ?: return@map null
                    val initTpl = attrOf(template, "initialization")
                    val startNumber = attrOf(template, "startNumber")?.toIntOrNull() ?: 1
                    val segDur = attrOf(template, "duration")?.toDoubleOrNull() ?: 0.0
                    val timescale = attrOf(template, "timescale")?.toDoubleOrNull() ?: 1.0
                    val count = if (durSec != null && segDur > 0) kotlin.math.ceil(durSec * timescale / segDur).toInt() else 0
                    if (count <= 0) return@map null
                    val fill: (String) -> String = { tpl ->
                        tpl.replace("\$RepresentationID\$", repId)
                            .replace("\$Bandwidth\$", bandwidth.toString())
                            .replace("\$Number\$", startNumber.toString())
                    }
                    init = initTpl?.let { resolveUrl(mpdUrl, joinBase(baseUrl, fill(it))) }
                    segs = (0 until count).map { i ->
                        val numberStr = (startNumber + i).toString()
                        val tpl = media.replace("\$RepresentationID\$", repId)
                            .replace("\$Bandwidth\$", bandwidth.toString())
                            .replace("\$Number\$", numberStr)
                            .replace("\$Time\$", (((i * segDur) / timescale).toLong()).toString())
                        resolveUrl(mpdUrl, joinBase(baseUrl, tpl))
                    }
                } else if (body.contains("SegmentBase") && baseUrl.isNotBlank()) {
                    // 单文件 Representation（SegmentBase/indexRange）：整文件直下
                    init = null
                    segs = listOf(resolveUrl(mpdUrl, baseUrl))
                } else {
                    return@map null
                }
                Cand(kind, bandwidth, init, segs, baseUrl)
            }.filterNotNull().filter { it.segs.isNotEmpty() }

            best.groupBy { it.kind }.forEach { (kind, list) ->
                val pick = list.maxByOrNull { it.bandwidth } ?: return@forEach
                tracks.add(DashTrack(kind, pick.init, pick.segs, pick.bandwidth))
            }
        }
        return tracks
    }

    private fun attrOf(attrs: String, name: String): String? =
        Regex(name + "=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    private fun joinBase(base: String, path: String): String =
        if (base.isBlank()) path else if (path.startsWith("http")) path else base.trimEnd('/') + "/" + path.trimStart('/')

    /** 把视频/音频多条 mp4 轨道合成一个文件 */
    private fun remuxToMp4(inputs: List<File>, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<MediaExtractor>()
        try {
            val trackMap = HashMap<Pair<Int, Int>, Int>()
            inputs.forEachIndexed { ei, file ->
                val ex = MediaExtractor(); ex.setDataSource(file.absolutePath); extractors.add(ex)
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        ex.selectTrack(i); trackMap[ei to i] = muxer.addTrack(format)
                    }
                }
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // 简单按时间戳合并：反复挑当前时间戳最小的轨道
            while (true) {
                var bestEx = -1; var bestTime = Long.MAX_VALUE
                extractors.forEachIndexed { ei, ex ->
                    val t = ex.sampleTime
                    if (t >= 0 && t < bestTime) { bestTime = t; bestEx = ei }
                }
                if (bestEx < 0) break
                val ex = extractors[bestEx]
                val ti = ex.sampleTrackIndex
                info.size = ex.readSampleData(buffer, 0)
                if (info.size < 0) { ex.advance(); continue }
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                info.offset = 0
                trackMap[bestEx to ti]?.let { muxer.writeSampleData(it, buffer, info) }
                ex.advance()
            }
        } finally {
            runCatching { muxer.stop() }; runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    /**
     * 逐分片重封装：每个 TS 分片独立用 MediaExtractor 读取，时间戳顺延后写入同一个 MP4。
     * 分片之间时间戳从 0 重新开始也不会丢内容，这是整段拼接方案「只有片头」的根因修复。
     */
    /** MP4 时长覆盖校验：明显短于清单总时长就判定封装不完整 */
    private fun mp4CoverageOk(mp4: File, expectedSec: Double): Boolean {
        if (!mp4.exists() || mp4.length() < 1024) return false
        if (expectedSec < 8.0) return true
        return runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(mp4.absolutePath)
                val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                ms >= (expectedSec * 0.75 * 1000).toLong()
            } finally { runCatching { r.release() } }
        }.getOrDefault(true)
    }

    private fun remuxSegmentsToMp4(files: List<File>, output: File): Boolean {
        if (output.exists()) output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var timeOffsetUs = 0L
        var written = 0L
        var mimeToMuxer: Map<String, MutableList<Int>> = emptyMap()
        try {
            for (file in files) {
                if (!file.exists() || file.length() == 0L) continue
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.absolutePath)
                    val trackMap = HashMap<Int, Int>()
                    if (!started) {
                        val m = LinkedHashMap<String, MutableList<Int>>()
                        for (i in 0 until ex.trackCount) {
                            val fmt = ex.getTrackFormat(i)
                            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                            val out = muxer.addTrack(fmt)
                            m.getOrPut(mime) { mutableListOf() }.add(out)
                            ex.selectTrack(i)
                            trackMap[i] = out
                        }
                        if (trackMap.isEmpty()) return false
                        mimeToMuxer = m
                        muxer.start(); started = true
                    } else {
                        // 后续分片：按 mime 顺序对齐到已创建的轨道
                        val used = HashMap<String, Int>()
                        for (i in 0 until ex.trackCount) {
                            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                            val list = mimeToMuxer[mime] ?: continue
                            val k = used.getOrDefault(mime, 0)
                            if (k >= list.size) continue
                            ex.selectTrack(i)
                            trackMap[i] = list[k]
                            used[mime] = k + 1
                        }
                        if (trackMap.isEmpty()) continue
                    }
                    val buf = ByteBuffer.allocate(8 * 1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    var lastTs = 0L
                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val ts = ex.sampleTime
                        if (ts > lastTs) lastTs = ts
                        val track = ex.sampleTrackIndex
                        val outTrack = trackMap[track]
                        if (outTrack == null) { ex.advance(); continue }
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = timeOffsetUs + ts
                        info.flags = ex.sampleFlags
                        runCatching { muxer.writeSampleData(outTrack, buf, info) }
                        written++
                        ex.advance()
                    }
                    timeOffsetUs += lastTs + 20_000L
                } finally {
                    runCatching { ex.release() }
                }
            }
            return written > 0
        } finally {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun remuxTsToMp4(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(input.absolutePath)
            val trackMap = HashMap<Int, Int>()
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i); trackMap[i] = muxer.addTrack(format)
                }
            }
            muxer.start()
            while (true) {
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime; info.flags = extractor.sampleFlags; info.offset = 0
                val trackIdx = extractor.sampleTrackIndex
                if (trackMap.containsKey(trackIdx)) muxer.writeSampleData(trackMap[trackIdx]!!, buffer, info)
                extractor.advance()
            }
            return true
        } finally { 
            runCatching { muxer.stop() }; runCatching { muxer.release() }; runCatching { extractor.release() } 
        }
    }
}
