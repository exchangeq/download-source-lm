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
