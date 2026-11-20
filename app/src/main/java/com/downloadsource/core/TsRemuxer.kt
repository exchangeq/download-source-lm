package com.downloadsource.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * TS → MP4 无损重封装（不转码，秒级完成）。
 * m3u8 分片合并出来的其实是 MPEG-TS，直接改后缀在部分播放器/相册里会黑屏或没声音；
 * 这里用系统 MediaExtractor 拆轨道 + MediaMuxer 写成标准 MP4（H.264/HEVC + AAC 均可）。
 */
object TsRemuxer {

    /** 判断内容是不是 MPEG-TS：0x47 同步字节，188 字节一包 */
    fun isTs(file: File): Boolean {
        if (!file.exists() || file.length() < 200) return false
        return runCatching {
            val buf = ByteArray(377)
            file.inputStream().use { it.read(buf) }
            buf[0] == 0x47.toByte() && buf[188] == 0x47.toByte()
        }.getOrDefault(false)
    }

    /** 成功返回新的 mp4 文件（已删除源 ts），失败返回 null 并保留原文件 */
    fun remux(ts: File, targetDir: File = ts.parentFile): File? = runCatching {
        if (!isTs(ts)) return null
        val base = ts.name.substringBeforeLast('.')
        var out = File(targetDir, "$base.mp4")
        var i = 1
        while (out.exists()) { out = File(targetDir, "$base ($i).mp4"); i++ }

        val extractor = MediaExtractor()
        extractor.setDataSource(ts.absolutePath)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 只取一路视频 + 一路音频，多音轨在 MP4 里容易踩兼容坑
        val pick = mutableListOf<Int>()
        var videoTrack = -1
        var audioTrack = -1
        for (t in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
            if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = t
            else if (audioTrack < 0 && mime.startsWith("audio/")) audioTrack = t
        }
        if (videoTrack < 0) {
            runCatching { extractor.release() }
            runCatching { muxer.release() }
            runCatching { out.delete() }
            return null
        }
        pick.add(videoTrack)
        if (audioTrack >= 0) pick.add(audioTrack)

        val outIndex = HashMap<Int, Int>()
        try {
            pick.forEach { t ->
                extractor.selectTrack(t)
                outIndex[t] = muxer.addTrack(extractor.getTrackFormat(t))
            }
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var progress = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = extractor.sampleTrackIndex
                val outT = outIndex[track] ?: break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = when {
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                muxer.writeSampleData(outT, buffer, info)
                extractor.advance()
                progress = extractor.sampleTime
            }
            if (progress <= 0) {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
                runCatching { extractor.release() }
                runCatching { out.delete() }
                return null
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
        if (out.exists() && out.length() > 1024) {
            ts.delete()
            out
        } else {
            out.delete()
            null
        }
    }.getOrNull()
}
