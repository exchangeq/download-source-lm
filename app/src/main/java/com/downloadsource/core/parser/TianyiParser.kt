package com.downloadsource.core.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object TianyiParser {
    private val client = OkHttpClient()

    suspend fun parse(url: String, pwd: String?): String = withContext(Dispatchers.IO) {
        val shareId = Regex("""/t/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: error("无法识别天翼云分享 ID")
        val infoApi = "https://cloud.189.cn/api/open/share/getShareInfoByCode.action?shareCode=$shareId"
        val req = Request.Builder().url(infoApi).build()
        val html = client.newCall(req).execute().use { it.body?.string() ?: "" }
        Regex("""https://[^"'\s]+\.file\.21cn\.com[^"'\s]+""").find(html)?.value 
            ?: error("天翼云盘解析需要登录或验证码，请尝试使用通用公共 API")
    }
}
