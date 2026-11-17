package com.downloadsource.core

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 嗅探结果增强：提前拿文件大小与时长，避免下载完才发现不是想要的 */
object MediaProbe {
    data class Info(val size: Long = -1L, val durationMs: Long = -1L)

    private val cache = ConcurrentHashMap<String, Info>()

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Info {
        cache[url]?.let { return it }
        val info = withTimeoutOrNull(8000L) {
            withContext(Dispatchers.IO) {
                val size = runCatching {
                    val builder = okhttp3.Request.Builder().url(url)
                        .header("Accept-Encoding", "identity")
                    headers.forEach { (k, v) -> builder.header(k, v) }
                    DownloadManager.httpClient.newCall(builder.head().build()).execute().use { resp ->
                        resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                }.getOrDefault(-1L)

                val duration = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (headers.isEmpty()) retriever.setDataSource(url)
                        else retriever.setDataSource(url, headers)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    } finally {
                        runCatching { retriever.release() }
                    }
                }.getOrDefault(-1L)

                Info(size, duration)
            }
        } ?: Info()
        cache[url] = info
        return info
    }

    fun cached(url: String): Info? = cache[url]

    /** 网页播放器上报的时长，先写缓存，列表打开就能立刻显示时长 */
    fun rememberDuration(url: String, durationMs: Long) {
        if (url.isBlank() || durationMs <= 0) return
        val old = cache[url]
        cache[url] = Info(old?.size ?: -1L, durationMs)
    }

    fun rememberSize(url: String, size: Long) {
        if (url.isBlank() || size <= 0) return
        val old = cache[url]
        cache[url] = Info(size, old?.durationMs ?: -1L)
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }
}
