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
        // Cookie 优先用保存的；没有就从浏览器会话里现取（分享直链通常要求登录态）
        val cookie = CookieStore.load(ctx, "quark")
            ?: runCatching {
                android.webkit.CookieManager.getInstance()
                    .getCookie("https://pan.quark.cn")
                    ?.takeIf { it.isNotBlank() }
                    ?.also { CookieStore.save(ctx, "quark", it) }
            }.getOrNull()
            ?: runCatching {
                android.webkit.CookieManager.getInstance()
                    .getCookie("https://drive-pc.quark.cn")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            ?: error("请先在浏览器里打开 pan.quark.cn 并登录（解析需要登录态 Cookie）")
        val shareId = Regex("""/s/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: error("链接识别失败")
        // 链接里带的提取码优先
        val pass = pwd?.takeIf { it.isNotBlank() }
            ?: Regex("""[?&]pwd=([a-zA-Z0-9]{4,8})""").find(url)?.groupValues?.get(1)
            ?: ""

        val tokenApi = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc"
        val tokenBody = """{"pwd_id":"$shareId","passcode":"$pass"}"""
        val tokenReq = Request.Builder().url(tokenApi).post(tokenBody.toRequestBody("application/json".toMediaType()))
            .header("Cookie", cookie).header("Referer", "https://pan.quark.cn/").header("Origin", "https://pan.quark.cn")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36")
            .build()
        val tokenJson = JSONObject(client.newCall(tokenReq).execute().body?.string() ?: "")
        val stoken = tokenJson.optJSONObject("data")?.optString("stoken")?.takeIf { it.isNotBlank() }
            ?: run {
                val msg = tokenJson.optString("message")
                if (msg.contains("密码") || msg.contains("passcode", true) || tokenJson.optInt("code") == 41008)
                    error("这个分享需要提取码，请在链接后补 ?pwd=xxxx 再试")
                error("获取 Token 失败（${tokenJson.optInt("code")}）：$msg。请先在浏览器里打开夸克分享页并登录")
            }

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
            val detailReq = Request.Builder().url(detailApi)
                .header("Cookie", cookie)
                .header("Referer", "https://pan.quark.cn/")
                .header("Origin", "https://pan.quark.cn")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val detailJson = JSONObject(client.newCall(detailReq).execute().body?.string() ?: "")
            if (detailJson.optInt("code", 0) != 0 && detailJson.optJSONObject("data") == null) {
                val msg = detailJson.optString("message")
                error("读取分享目录失败（${detailJson.optInt("code")}）：$msg")
            }
            val dataObj = detailJson.optJSONObject("data")
                ?: run {
                    val msg = detailJson.optString("message")
                    error("读取分享目录失败：$msg（Cookie 可能已过期，请在浏览器重新登录夸克）")
                }
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
                    // 分享文件下载接口有多个版本，逐个尝试，命中即用
                    var dlUrl: String? = null
                    for (api in listOf(
                        "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=$shareId&stoken=$stoken",
                        "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc",
                        "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc"
                    )) {
                        val dlBody = """{"fids":["$fid"],"pwd_id":"$shareId","stoken":"$stoken"}"""
                        val dlReq = Request.Builder().url(api)
                            .post(dlBody.toRequestBody("application/json".toMediaType()))
                            .header("Cookie", cookie).header("Referer", "https://pan.quark.cn/")
                            .header("Origin", "https://pan.quark.cn").header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36")
                            .build()
                        val txt = runCatching { client.newCall(dlReq).execute().use { it.body?.string() ?: "" } }.getOrDefault("")
                        val json = runCatching { JSONObject(txt) }.getOrNull()
                        dlUrl = json?.optJSONArray("data")?.let { arr ->
                            if (arr.length() == 0) null else arr.optJSONObject(0)?.optString("download_url")
                        }?.takeIf { it.isNotBlank() } ?: json?.optJSONObject("data")?.optString("download_url")?.takeIf { it.isNotBlank() }
                        if (dlUrl != null) break
                    }
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
