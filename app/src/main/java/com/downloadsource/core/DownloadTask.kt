package com.downloadsource.core

import java.util.UUID

enum class TaskStatus { Pending, Running, Paused, Completed, Failed }

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val dirPath: String = "",
    val threads: Int = 8,
    val speedLimitKb: Long = 0L,
    val headers: Map<String, String> = emptyMap(), // 新增：自定义请求头防 403
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speed: Long = 0L,
    val status: TaskStatus = TaskStatus.Pending,
    val error: String? = null,
    val outputPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val sizeText: String get() = if (totalBytes <= 0) "--" else "${fmt(downloadedBytes)} / ${fmt(totalBytes)}"
    val speedText: String get() = if (status == TaskStatus.Running && speed > 0) "${fmt(speed)}/s" else ""
    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1.0 * (1L shl 30)))
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1.0 * (1L shl 20)))
        b >= 1L shl 10 -> String.format("%.1f KB", b / (1.0 * (1L shl 10)))
        else -> "$b B"
    }
}
