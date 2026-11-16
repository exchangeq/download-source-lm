package com.downloadsource.core.parser

import android.content.Context
import com.downloadsource.core.DiskParser
import com.downloadsource.storage.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object QuarkParser {
    private val client = OkHttpClient()

    suspend fun parse(ctx: Context, url: String, pwd: String?): List<DiskParser.FileInfo> = withContext(Dispatchers.IO) {
        val cookie = CookieStore.load(ctx, "quark") ?: error("请先在浏览器登录夸克网盘")
        val shareId = Regex("""/s/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: error("链接识别失败")

        val tokenApi = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc"
        val tokenBody = """{"pwd_id":"$shareId","passcode":"${pwd ?: ""}"}"""
        val tokenReq = Request.Builder().url(tokenApi).post(tokenBody.toRequestBody("application/json".toMediaType())).header("Cookie", cookie).build()
        val tokenJson = JSONObject(client.newCall(tokenReq).execute().body?.string() ?: "")
        val stoken = tokenJson.optJSONObject("data")?.optString("stoken") ?: error("获取Token失败，Cookie失效")

        val results = mutableListOf<DiskParser.FileInfo>()
        // 开启分页拉取，每次拉 50 个
        fetchFolderWithPagination(cookie, shareId, stoken, "0", 1, results)
        results
    }

    private fun fetchFolderWithPagination(cookie: String, shareId: String, stoken: String, pdirFid: String, startPage: Int, results: MutableList<DiskParser.FileInfo>) {
        var page = startPage
        var hasMore = true
        
        while (hasMore) {
            val detailApi = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=$shareId&stoken=$stoken&pdir_fid=$pdirFid&force=0&_page=$page&_size=50"
            val detailReq = Request.Builder().url(detailApi).header("Cookie", cookie).build()
            val detailJson = JSONObject(client.newCall(detailReq).execute().body?.string() ?: "")
            val dataObj = detailJson.optJSONObject("data") ?: break
            val list = dataObj.optJSONArray("list") ?: break
            
            if (list.length() == 0) { hasMore = false; break }

            for (i in 0 until list.length()) {
                val file = list.getJSONObject(i)
                val fid = file.optString("fid")
                val name = file.optString("file_name")
                val isDir = file.optBoolean("dir")
                val size = file.optLong("size", 0L)

                if (isDir) {
                    // 递归进入子文件夹
                    fetchFolderWithPagination(cookie, shareId, stoken, fid, 1, results)
                } else {
                    val dlApi = "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc"
                    val dlBody = """{"fids":["$fid"]}"""
                    val dlReq = Request.Builder().url(dlApi).post(dlBody.toRequestBody("application/json".toMediaType())).header("Cookie", cookie).build()
                    val dlJson = JSONObject(client.newCall(dlReq).execute().body?.string() ?: "")
                    val dlUrl = dlJson.optJSONArray("data")?.optJSONObject(0)?.optString("download_url")
                    if (!dlUrl.isNullOrBlank()) {
                        results.add(DiskParser.FileInfo(name, dlUrl, size))
                    }
                }
            }
            
            // 如果返回的数量小于 50，说明没有下一页了
            if (list.length() < 50) hasMore = false
            else page++
        }
    }
}
