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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TsSegment(val url: String, val key: ByteArray?, val iv: ByteArray?)

class Segmenter(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) {
    private val activeCalls = ConcurrentHashMap<String, MutableList<okhttp3.Call>>()

    fun cancelTask(taskId: String) {
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    suspend fun download(taskId: String, url: String, targetDir: File, fileName: String, parts: Int, speedLimitKb: Long, customHeaders: Map<String, String>, onTick: (Long, Long, Long) -> Unit): File = coroutineScope {
        val headers = okhttp3.Headers.Builder().apply { customHeaders.forEach { (k, v) -> add(k, v) } }.build()
        
        if (url.contains(".m3u8") || url.endsWith(".ts", true)) return@coroutineScope downloadM3u8(taskId, url, targetDir, fileName, parts, headers, onTick)

        // 用一个字节的 Range 请求探测长度与分段能力，兼容禁 HEAD 的站点
        val probe = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).headers(headers)
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 206) {
                    val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    FileMeta(total, total > 0)
                } else {
                    val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    FileMeta(total, false)
                }
            }
        }
        val total = probe.total
        if (total <= 0) error("获取文件大小失败（服务器未返回长度）")
        
        val n = if (probe.rangeable && total > 4L shl 20) parts.coerceAtMost(16) else 1
        val tmpDir = File(targetDir, ".${fileName}.parts").apply { mkdirs() }
        val downloaded = AtomicLong(0)

        val bucket = TokenBucket(if (speedLimitKb > 0) speedLimitKb * 1024 else 0)
        var lastTick = System.currentTimeMillis(); var lastBytes = 0L
        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis(); val cur = downloaded.get()
                val speed = if (now > lastTick) (cur - lastBytes) * 1000 / (now - lastTick) else 0
                lastTick = now; lastBytes = cur
                onTick(cur, total, speed)
            }
        }

        try {
            (0 until n).map { i ->
                async(Dispatchers.IO) {
                    val start = i * total / n; val end = if (i == n - 1) total - 1 else (i + 1) * total / n - 1
                    retryDownloadRange(
                        taskId, url, start, end, File(tmpDir, "$fileName.part$i"),
                        downloaded, bucket, headers
                    )
                }
            }.awaitAll()
            ticker.cancel()
            
            // 修复：合并前严格校验分片大小
            var actualSize = 0L
            for (i in 0 until n) actualSize += File(tmpDir, "$fileName.part$i").length()
            if (actualSize != total) error("文件下载不完整 (预期: $total, 实际: $actualSize)，请重试")

            val partPaths = (0 until n).map { File(tmpDir, "$fileName.part$it").absolutePath }.toTypedArray()
            val out = File(targetDir, fileName)
            if (!ZeroCore.mergeFiles(partPaths, out.absolutePath)) error("C++底层合并失败，请检查存储空间")
            tmpDir.deleteRecursively()
            onTick(total, total, 0)
            out
        } catch (e: Exception) { ticker.cancel(); throw e }
    }

    private suspend fun retryDownloadRange(taskId: String, url: String, start: Long, end: Long, file: File, downloaded: AtomicLong, bucket: TokenBucket, headers: okhttp3.Headers) {
        var retries = 5
        while (retries > 0) {
            if (!currentCoroutineContext().isActive) return
            try { downloadRange(taskId, url, start, end, file, downloaded, bucket, headers); return } 
            catch (e: Exception) { 
                if (e is CancellationException) throw e
                retries--; if (retries == 0) throw e; delay(1500)
            }
        }
    }

    private suspend fun downloadRange(taskId: String, url: String, start: Long, end: Long, file: File, downloaded: AtomicLong, bucket: TokenBucket, headers: okhttp3.Headers) {
        val existing = if (file.exists()) file.length() else 0L
        val realStart = start + existing
        if (realStart > end) { downloaded.addAndGet(existing); return }
        if (existing > 0 && downloaded.get() == 0L) downloaded.addAndGet(existing)
        
        val req = Request.Builder().url(url).headers(headers).header("Range", "bytes=$realStart-$end").build()
        val call = client.newCall(req)
        activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)
        
        try {
            call.execute().use { resp ->
                // 请求了 Range 就必须拿到 206，否则说明服务端忽略了区间，写下去会把文件写坏
                if (resp.code != 206 && !(start == 0L && resp.code == 200)) {
                    throw Exception("分片失败，服务端未按区间返回：HTTP ${resp.code}")
                }
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(existing)
                    resp.body?.byteStream()?.use { ins ->
                        val buf = ByteArray(128 * 1024)
                        var bytesSinceStart = 0L
                        val startTime = System.currentTimeMillis()

                        while (true) {
                            if (!currentCoroutineContext().isActive) break
                            val r = ins.read(buf)
                            if (r <= 0) break
                            raf.write(buf, 0, r)
                            downloaded.addAndGet(r.toLong())
                            bytesSinceStart += r

                            bucket.take(r)
                        }
                    }
                    raf.fd.sync()
                }
            }
        } finally {
            activeCalls[taskId]?.remove(call)
        }
    }

    // M3U8 下载逻辑保持不变，直接复用上一版的完美解密引擎...
    private suspend fun downloadM3u8(taskId: String, url: String, targetDir: File, fileName: String, parts: Int, headers: okhttp3.Headers, onTick: (Long, Long, Long) -> Unit): File = coroutineScope {
        var finalUrl = url
        if (url.endsWith(".ts", true)) finalUrl = url.substringBeforeLast("/").plus("/index.m3u8")
        
        val segments = parseM3u8(finalUrl, mutableSetOf())
        if (segments.isEmpty()) error("未找到有效的 TS 分片")
        
        val tmpDir = File(targetDir, ".${fileName}.ts_parts").apply { mkdirs() }
        val downloaded = AtomicLong(0)
        val total = segments.size.toLong()
        val ticker = launch(Dispatchers.Default) { while (isActive) { delay(1000); onTick(downloaded.get(), total, 0) } }

        val semaphore = Semaphore(parts)
        segments.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val partFile = File(tmpDir, "$index.ts")
                    if (!partFile.exists()) {
                        var r = 3
                        while (r > 0) {
                            if (!isActive) return@withPermit
                            var callRef: okhttp3.Call? = null
                            try {
                                val call = client.newCall(Request.Builder().url(segment.url).headers(headers).build())
                                callRef = call
                                activeCalls.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>()) }.add(call)
                                call.execute().use { resp ->
                                    var bytes = resp.body?.bytes() ?: ByteArray(0)
                                    if (segment.key != null && segment.iv != null) bytes = decryptAES(bytes, segment.key, segment.iv)
                                    partFile.writeBytes(bytes)
                                    java.io.FileOutputStream(partFile, true).use { it.fd.sync() }
                                }
                                break
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                r--; if (r == 0) throw e; delay(1000)
                            } finally {
                                // 只移除本次请求，不能清空同任务其它在途分片
                                callRef?.let { activeCalls[taskId]?.remove(it) }
                            }
                        }
                    }
                    downloaded.incrementAndGet()
                }
            }
        }.awaitAll()
        ticker.cancel()

        val mergedTs = File(targetDir, "$fileName.tmp_merged.ts")
        if (!ZeroCore.mergeFiles(segments.indices.map { File(tmpDir, "$it.ts").absolutePath }.toTypedArray(), mergedTs.absolutePath)) error("TS 合成失败")

        val outMp4 = File(targetDir, if (fileName.endsWith(".mp4")) fileName else "$fileName.mp4")
        val success = withContext(Dispatchers.IO) { 
            try { remuxTsToMp4(mergedTs, outMp4); true } 
            catch (e: Exception) { false } 
        }
        
        tmpDir.deleteRecursively()
        onTick(total, total, 0)
        
        if (!success) {
            val fallbackTs = File(targetDir, if (fileName.endsWith(".ts")) fileName else "$fileName.ts")
            if (mergedTs.exists()) mergedTs.renameTo(fallbackTs)
            throw Exception("MP4 封装失败，已保留 .ts 原文件 (´･_･`)")
        }
        mergedTs.delete()
        outMp4
    }

    private suspend fun parseM3u8(url: String, visited: MutableSet<String>): List<TsSegment> = withContext(Dispatchers.IO) {
        if (visited.contains(url)) return@withContext emptyList()
        visited.add(url)
        val text = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "" }
        val lines = text.lines()

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val subUrl = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (subUrl != null) return@withContext parseM3u8(resolveUrl(url, subUrl), visited)
        }

        var currentKey: ByteArray? = null
        var currentIv: ByteArray? = null
        var mediaSequence = 0L
        var segmentIndex = 0L
        val result = mutableListOf<TsSegment>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = trimmed.substringAfter(":").toLongOrNull() ?: 0L
            } else if (trimmed.startsWith("#EXT-X-KEY:")) {
                val method = Regex("METHOD=([^,]+)").find(trimmed)?.groupValues?.get(1) ?: "NONE"
                if (method == "AES-128") {
                    val keyUri = Regex("URI=\"(.*?)\"").find(trimmed)?.groupValues?.get(1)
                    val ivStr = Regex("IV=0x(.*?)(,|$)").find(trimmed)?.groupValues?.get(1)
                    if (keyUri != null) {
                        currentKey = client.newCall(Request.Builder().url(resolveUrl(url, keyUri)).build()).execute().use { it.body?.bytes() }
                        currentIv = if (ivStr != null) hexStringToByteArray(ivStr) else null
                    } else { currentKey = null; currentIv = null }
                } else if (method == "NONE") { currentKey = null; currentIv = null }
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val absUrl = resolveUrl(url, trimmed)
                if (absUrl.contains(".m3u8", ignoreCase = true)) {
                    result.addAll(parseM3u8(absUrl, visited))
                } else {
                    val ivToUse = currentIv ?: if (currentKey != null) {
                        val seq = mediaSequence + segmentIndex
                        ByteArray(16).apply {
                            this[8] = (seq shr 56).toByte(); this[9] = (seq shr 48).toByte(); this[10] = (seq shr 40).toByte(); this[11] = (seq shr 32).toByte()
                            this[12] = (seq shr 24).toByte(); this[13] = (seq shr 16).toByte(); this[14] = (seq shr 8).toByte(); this[15] = seq.toByte()
                        }
                    } else null
                    result.add(TsSegment(absUrl, currentKey, ivToUse))
                }
                segmentIndex++
            }
        }
        return@withContext result
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return java.net.URI(baseUrl).resolve(relativeUrl).toString()
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
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

    private fun remuxTsToMp4(input: File, output: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(input.absolutePath)
            val trackMap = HashMap<Int, Int>()
            val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
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


data class FileMeta(val total: Long, val rangeable: Boolean)

/**
 * 任务级令牌桶：所有分片共享同一个实例，限速才按任务总量生效。
 */
class TokenBucket(private val ratePerSec: Long) {
    private val tokens = AtomicLong(ratePerSec)
    @Volatile private var lastNs = System.nanoTime()

    suspend fun take(n: Int) {
        if (ratePerSec <= 0L) return
        while (true) {
            val now = System.nanoTime()
            val delta = (now - lastNs) / 1_000_000_000.0
            val add = (delta * ratePerSec).toLong()
            if (add > 0L) {
                lastNs = now
                tokens.addAndGet(add)
            }
            if (tokens.get() >= n) {
                tokens.addAndGet(-n.toLong())
                return
            }
            delay((((n - tokens.get()) * 1000L) / ratePerSec).coerceAtLeast(1L))
        }
    }
}
