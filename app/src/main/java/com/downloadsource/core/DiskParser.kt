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
            NetDiskParser.DiskType.BAIDU ->
                error("百度网盘需要登录+验证，暂不做后台解析：请用内置浏览器打开分享页，登录后点嗅探下载")
            NetDiskParser.DiskType.ALIYUN ->
                error("阿里云盘需要在浏览器登录后取直链：请用内置浏览器打开分享页再嗅探")
            NetDiskParser.DiskType.P115 ->
                error("115 网盘需要会员直链，请用内置浏览器登录后嗅探下载")
            else -> emptyList()
        }
    }
}
