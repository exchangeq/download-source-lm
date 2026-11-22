package com.downloadsource.core

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32

object ZeroCore {
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("zerocore")
            isLoaded = true
        } catch (e: Throwable) {
            Log.e("ZeroCore", "Native library failed to load, falling back to Java IO", e)
            isLoaded = false
        }
    }

    // --- Native 方法 (私有) ---
    private external fun nativeFallocate(path: String, size: Long): Boolean
    private external fun nativeMergeFiles(parts: Array<String>, output: String): Boolean
    private external fun nativeCrc32(path: String): Long
    private external fun nativeSniffExt(head: ByteArray): String?
    private external fun nativeProbeZipKind(path: String): String?
    private external fun nativeFitScale(contentW: Int, deviceW: Int): Int
    private external fun nativeDarkenRgb565(pixels: ShortArray, count: Int, factor: Int)
    private external fun nativeFrameStats(pixels: IntArray, count: Int): FloatArray?

    // --- 暴露给业务的降级方法 ---

    fun fallocate(path: String, size: Long): Boolean {
        if (isLoaded) {
            try {
                if (nativeFallocate(path, size)) return true
            } catch (e: Throwable) {}
        }
        // 降级方案：使用 Java 的 setLength
        return try {
            val f = File(path)
            if (!f.exists()) f.createNewFile()
            java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
            true
        } catch (e: Exception) { false }
    }

    fun mergeFiles(parts: Array<String>, output: String): Boolean {
        if (isLoaded) {
            try {
                if (nativeMergeFiles(parts, output)) return true
            } catch (e: Throwable) {}
        }
        // 降级方案：Java 流复制
        return try {
            FileOutputStream(output).use { out ->
                val buf = ByteArray(1024 * 1024)
                for (part in parts) {
                    FileInputStream(part).use { ins ->
                        var len: Int
                        while (ins.read(buf).also { len = it } > 0) {
                            out.write(buf, 0, len)
                        }
                    }
                }
                out.fd.sync() // 强制刷盘
            }
            true
        } catch (e: Exception) { false }
    }

    /** C 层魔数嗅探：返回不带点的扩展名，识别不了返回 null */
    fun sniffExt(head: ByteArray): String? {
        if (isLoaded) runCatching { nativeSniffExt(head)?.takeIf { it.isNotBlank() }?.let { return it } }
        return null
    }

    /** C 层 ZIP 容器内探测：apk / apkm / mpkg / epub / docx / xlsx / pptx / cbz */
    fun probeZipKind(path: String): String? {
        if (isLoaded) runCatching { nativeProbeZipKind(path)?.takeIf { it.isNotBlank() }?.let { return it } }
        return null
    }

    /** C 层计算整页适配缩放百分比（内容超出屏宽时用） */
    fun fitScale(contentW: Int, deviceW: Int): Int {
        if (isLoaded) runCatching { nativeFitScale(contentW, deviceW).takeIf { it in 10..100 }?.let { return it } }
        if (contentW <= 0 || deviceW <= 0) return 100
        return ((deviceW.toDouble() * 100.0 / contentW).toInt()).coerceIn(10, 100)
    }

    /** 一帧画面的亮度统计：平均亮度 / 近黑占比 / 暗色占比（黑屏自检用） */
    data class FrameStats(val meanLuma: Float, val blackRatio: Float, val darkRatio: Float)

    fun frameStats(pixels: IntArray): FrameStats {
        if (isLoaded) runCatching {
            nativeFrameStats(pixels, pixels.size)?.takeIf { it.size >= 3 }?.let {
                return FrameStats(it[0], it[1], it[2])
            }
        }
        if (pixels.isEmpty()) return FrameStats(255f, 0f, 0f)
        var sum = 0L
        var black = 0L
        var dark = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            sum += l
            if (l < 18) black++ else if (l < 60) dark++
        }
        val n = pixels.size.toFloat()
        return FrameStats(sum / n, black / n, dark / n)
    }

    /** C 层逐像素压暗 RGB565（供深色导出用） */
    fun darkenRgb565(pixels: ShortArray, factor: Int) {
        if (isLoaded) runCatching { nativeDarkenRgb565(pixels, pixels.size, factor); return }
        val f = factor.coerceIn(0, 100)
        for (i in pixels.indices) {
            val p = pixels[i].toInt()
            val r = ((p shr 11) and 0x1F) * f / 100
            val g = ((p shr 5) and 0x3F) * f / 100
            val b = (p and 0x1F) * f / 100
            pixels[i] = ((r shl 11) or (g shl 5) or b).toShort()
        }
    }

    fun crc32(path: String): Long {
        if (isLoaded) {
            try {
                val ret = nativeCrc32(path)
                if (ret != -1L) return ret
            } catch (e: Throwable) {}
        }
        // 降级方案：Java 自带 CRC32
        return try {
            val crc = CRC32()
            FileInputStream(path).use { ins ->
                val buf = ByteArray(512 * 1024)
                var len: Int
                while (ins.read(buf).also { len = it } > 0) {
                    crc.update(buf, 0, len)
                }
            }
            crc.value
        } catch (e: Exception) { -1L }
    }
}
