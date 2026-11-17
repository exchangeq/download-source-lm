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

data class TsSegment(val url: String, val key: ByteArray?, val iv: ByteArray?, val range: String? = null, val init: ByteArray? = null)

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
    private val activeCalls = ConcurrentHashMap<String, MutableList<okhttp3.Call>>()

    fun cancelTask(taskId: String) {
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    suspend fun download(taskId: String, url: String, outFile: File, parts: Int, speedLimitKb: Long, customHeaders: Map<String, String>, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        val headers = okhttp3.Headers.Builder().apply { customHeaders.forEach { (k, v) -> add(k, v) } }.build()
        
        if (url.contains(".m3u8", true)) return@coroutineScope downloadM3u8(taskId, url, outFile, parts, headers, onTick)
        if (url.contains(".mpd", true)) return@coroutineScope downloadDash(taskId, url, outFile, parts, headers, onTick)
        if (url.endsWith(".ts", true) && !outFile.name.endsWith(".ts", true)) return@coroutineScope downloadM3u8(taskId, url, outFile, parts, headers, onTick)

        // 核心修复：智能探测文件大小与 Range 支持
        val (total, supportsRange) = probeFileSize(url, headers)
        
        // 如果探测不到大小，直接降级为流式下载，绝不报错
        if (total <= 0) {
            return@coroutineScope streamDownload(taskId, url, outFile, headers, onTick)
        }
        
        // 32 路并发抓取；按 1MB/片下限收敛，避免碎成几万个小块
        val maxBySize = (total / (1L shl 20)).toInt().coerceAtLeast(1)
        // 不限速时大文件自动加路数（8MB 以上至少 24 路），小文件保持轻量
        val autoParts = when {
            total > 64L shl 20 -> 32
            total > 8L shl 20 -> maxOf(parts, 24)
            else -> parts
        }
        val n = if (supportsRange && total > 2L shl 20) autoParts.coerceIn(1, 32).coerceAtMost(maxBySize) else 1
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
            client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val range = resp.header("Accept-Ranges")?.contains("bytes", true) == true
                    if (len > 0) return@withContext Pair(len, range)
                }
            }
        } catch (e: Exception) {}

        // 2. 尝试 GET Range: 0-0
        try {
            val rangeReq = Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
            client.newCall(rangeReq).execute().use { resp ->
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
        val req = Request.Builder().url(url).headers(headers).header("Accept-Encoding", "identity").build()
        val call = client.newCall(req)
        activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)
        val partFile = File(outFile.parentFile, ".${outFile.name}.part")
        
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    resp.body?.byteStream()?.use { ins ->
                        partFile.outputStream().use { out ->
                            val buf = ByteArray(1024 * 1024)
                            var downloaded = 0L
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
            if (!partFile.renameTo(outFile)) { partFile.copyTo(outFile, true); partFile.delete() }
            onTick(outFile.length(), outFile.length(), 0L, emptyList())
            outFile
        } finally {
            activeCalls[taskId]?.remove(call)
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
        val call = client.newCall(req)
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
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(start)
                    resp.body?.byteStream()?.use { ins ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            if (!currentCoroutineContext().isActive) break
                            val r = ins.read(buf)
                            if (r <= 0) break
                            raf.write(buf, 0, r)
                            downloaded.addAndGet(r.toLong())
                            rateLimiter.acquire(r.toLong())
                        }
                    }
                    raf.fd.sync()
                }
            }
        } finally {
            activeCalls[taskId]?.remove(call)
        }
    }

    private suspend fun downloadM3u8(taskId: String, url: String, outFile: File, parts: Int, headers: okhttp3.Headers, onTick: (Long, Long, Long, List<Float>) -> Unit): File = coroutineScope {
        var finalUrl = url
        if (url.endsWith(".ts", true)) finalUrl = url.substringBeforeLast("/").plus("/index.m3u8")
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
                                val call = client.newCall(rb.build())
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

        val mergedTs = File(outFile.parentFile, "${outFile.name}.tmp_merged.ts")
        if (!ZeroCore.mergeFiles(segments.indices.map { File(tmpDir, "$it.ts").absolutePath }.toTypedArray(), mergedTs.absolutePath)) error("TS 合成失败")

        val success = withContext(Dispatchers.IO) { 
            try { remuxTsToMp4(mergedTs, outFile); true } catch (e: Exception) { false } 
        }
        tmpDir.deleteRecursively()
        onTick(total, total, 0, emptyList())
        if (!success) {
            val fallbackTs = File(outFile.parentFile, outFile.name.substringBeforeLast('.') + ".ts")
            if (mergedTs.exists()) mergedTs.renameTo(fallbackTs)
            if (fallbackTs.exists()) return@coroutineScope fallbackTs
            throw Exception("TS 合并失败 (´･_･`)")
        }
        mergedTs.delete()
        outFile
    }

    private suspend fun parseM3u8(url: String, visited: MutableSet<String>, headers: okhttp3.Headers): List<TsSegment> = withContext(Dispatchers.IO) {
        if (visited.contains(url)) return@withContext emptyList()
        visited.add(url)
        val text = runCatching {
            client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { it.body?.string() ?: "" }
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
                                    client.newCall(Request.Builder().url(absKey).headers(headers).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
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
                        result.add(TsSegment(absUrl, currentKey, ivToUse, pendingRange, null))
                        pendingRange = null
                    }
                    segmentIndex++
                }
            }
        }
        // fMP4 初始化段（EXT-X-MAP）放在最前，否则合并后无法播放
        if (mapUri != null) {
            runCatching {
                client.newCall(Request.Builder().url(mapUri!!).headers(headers).build()).execute().use { resp ->
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
            client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { it.body?.string() ?: "" }
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
                        client.newCall(Request.Builder().url(track.initUrl).headers(headers).build()).execute().use { r ->
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
                                        client.newCall(Request.Builder().url(segUrl).headers(headers).build()).execute().use { r ->
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
                val init: String?
                val segs: List<String>
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
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
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

    private fun remuxTsToMp4(input: File, output: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(input.absolutePath)
            val trackMap = HashMap<Int, Int>()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
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
        } finally { 
            runCatching { muxer.stop() }; runCatching { muxer.release() }; runCatching { extractor.release() } 
        }
    }
}
