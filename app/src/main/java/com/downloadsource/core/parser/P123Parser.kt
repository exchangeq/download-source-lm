package com.downloadsource.core.parser

import com.downloadsource.core.DiskParser // 修复：导入 DiskParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object P123Parser {
    private val client = OkHttpClient()
    suspend fun parse(url: String, pwd: String?): List<DiskParser.FileInfo> = withContext(Dispatchers.IO) {
        val shareKey = Regex("""/(?:s|share)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: error("123云盘链接识别失败")
        val api = "https://www.123pan.com/api/share/download/info"
        val jsonBody = """{"shareKey":"$shareKey","pwd":"${pwd ?: ""}","isHome":0}"""
        val req = Request.Builder().url(api).post(jsonBody.toRequestBody("application/json".toMediaType())).build()
        val resp = client.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "")
        if (json.optInt("code") != 0) error(json.optString("message", "解析失败"))

        val data = json.getJSONObject("data")
        val list = data.optJSONArray("InfoList") ?: error("文件列表为空")
        val results = mutableListOf<DiskParser.FileInfo>()

        for (i in 0 until list.length()) {
            val file = list.getJSONObject(i)
            val fileId = file.optString("FileId")
            val name = file.optString("FileName")
            val size = file.optLong("Size", 0L)
            
            if (name.contains("/")) continue // 简单过滤文件夹

            val dlBody = """{"shareKey":"$shareKey","fileId":$fileId,"pwd":"${pwd ?: ""}","isHome":0}"""
            val dlReq = Request.Builder().url(api).post(dlBody.toRequestBody("application/json".toMediaType())).build()
            val dlJson = JSONObject(client.newCall(dlReq).execute().body?.string() ?: "")
            val dlUrl = dlJson.getJSONObject("data").optString("DownloadURL")
            if (dlUrl.isNotBlank()) results.add(DiskParser.FileInfo(name, dlUrl, size))
        }
        results
    }
}
