package com.downloadsource.core

import java.net.URLEncoder

object NetDiskParser {
    enum class DiskType(val key: String, val label: String) {
        QUARK("quark", "夸克网盘"),
        P123("123pan", "123云盘"),
        LZ("lz", "蓝奏云"),
        UNKNOWN("", "通用直链")
    }

    data class ShareInfo(val type: DiskType, val url: String, val pwd: String?)

    // 智能提取提取码
    fun detect(raw: String): ShareInfo {
        val text = raw.trim()
        val pwdRegex = Regex("""(?i)(?:提取码|密码|pwd|code)[\s:：=]*([a-zA-Z0-9]{4})""")
        val pwd = pwdRegex.find(text)?.groupValues?.get(1)
            ?: Regex("""(?i)[?&]pwd=([a-zA-Z0-9]{4})""").find(text)?.groupValues?.get(1)

        val type = when {
            text.contains("pan.quark.cn") || text.contains("drive-pc.quark.cn") -> DiskType.QUARK
            text.contains("123pan.com") || text.contains("123pan.cn") -> DiskType.P123
            text.contains("lanzou") || text.contains("lanpw") || text.contains("woozooo") -> DiskType.LZ
            else -> DiskType.UNKNOWN
        }
        val url = Regex("""https?://[^\s]+""").find(text)?.value ?: text
        return ShareInfo(type, url, pwd)
    }
}
