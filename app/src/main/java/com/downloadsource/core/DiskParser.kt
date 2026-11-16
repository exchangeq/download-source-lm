package com.downloadsource.core

import android.content.Context
import com.downloadsource.core.parser.*

object DiskParser {
    // 返回：文件名、下载链接、大小
    data class FileInfo(val name: String, val url: String, val size: Long = 0L)

    suspend fun parse(ctx: Context, shareInfo: NetDiskParser.ShareInfo): List<FileInfo> {
        return when (shareInfo.type) {
            NetDiskParser.DiskType.QUARK -> QuarkParser.parse(ctx, shareInfo.url, shareInfo.pwd)
            NetDiskParser.DiskType.P123 -> P123Parser.parse(shareInfo.url, shareInfo.pwd)
            NetDiskParser.DiskType.LZ -> LanzouParser.parse(shareInfo.url, shareInfo.pwd)
            else -> emptyList()
        }
    }
}
