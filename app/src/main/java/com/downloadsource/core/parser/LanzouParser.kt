package com.downloadsource.core.parser

import com.downloadsource.core.DiskParser // 修复：导入 DiskParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object LanzouParser {
    private val client = OkHttpClient()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun parse(url: String, pwd: String?): List<DiskParser.FileInfo> {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: error("获取页面失败")
                val signMatch = Regex("""'sign'\s*:\s*'([^']+)'""").find(html)
                val webSignMatch = Regex("""'websignkey'\s*:\s*'([^']+)'""").find(html)
                if (signMatch != null && webSignMatch != null) {
                    val sign = signMatch.groupValues[1]
                    val webSignKey = webSignMatch.groupValues[1]
                    val postBody = "action=downprocess&sign=$sign&ves=1&websignkey=$webSignKey&websign=&kd=1".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    val ajaxReq = Request.Builder().url("https://developer.lanzouw.com/ajaxm.php").header("User-Agent", UA).header("Referer", url).post(postBody).build()
                    client.newCall(ajaxReq).execute().use { ajaxResp ->
                        val json = JSONObject(ajaxResp.body?.string() ?: "")
                        if (json.optInt("zt") == 1) {
                            val direct = "${json.getString("dom")}/file/${json.getString("url")}"
                            return listOf(DiskParser.FileInfo("Lanzou_File_${System.currentTimeMillis()}.zip", direct))
                        }
                    }
                }
            }
        } catch (e: Exception) { error("蓝奏云解析失败：${e.message}") }
        error("未找到有效下载参数")
    }
}
