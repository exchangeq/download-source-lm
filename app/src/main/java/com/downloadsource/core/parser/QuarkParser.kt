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
        // 第一次失败：换一份最新的浏览器 Cookie 再试一次（旧 Cookie 过期是解析率低的主因）
        var stoken = tokenJson.optJSONObject("data")?.optString("stoken")?.takeIf { it.isNotBlank() }
        if (stoken == null) {
            val fresh = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://pan.quark.cn")
            }.getOrNull()
            if (!fresh.isNullOrBlank() && fresh != cookie) {
                CookieStore.save(ctx, "quark", fresh)
                val retryReq = Request.Builder().url(tokenApi).post(tokenBody.toRequestBody("application/json".toMediaType()))
                    .header("Cookie", fresh).header("Referer", "https://pan.quark.cn/").header("Origin", "https://pan.quark.cn")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36")
                    .build()
                val retryJson = JSONObject(client.newCall(retryReq).execute().body?.string() ?: "")
                stoken = retryJson.optJSONObject("data")?.optString("stoken")?.takeIf { it.isNotBlank() }
                if (stoken != null) {
                    return@withContext buildResult(ctx, fresh, shareId, stoken)
                }
            }
        }
        val failure = stoken ?: run {
                val msg = tokenJson.optString("message")
                if (msg.contains("密码") || msg.contains("passcode", true) || tokenJson.optInt("code") == 41008)
                    error("这个分享需要提取码，请在链接后补 ?pwd=xxxx 再试")
                error("获取 Token 失败（${tokenJson.optInt("code")}）：$msg。请先在浏览器里打开夸克分享页并登录")
            }

        buildResult(ctx, cookie, shareId, failure)
    }

    /** 缓存 → 目录树 → 批量直链 */
    private fun buildResult(ctx: Context, cookie: String, shareId: String, stoken: String): List<DiskParser.FileInfo> {
        readCache(ctx, shareId)?.let { return it }
        val entries = mutableListOf<Pair<String, Entry>>()
        collectTree(cookie, shareId, stoken, "0", "", entries)
        if (entries.isEmpty()) error("分享里没有可下载的文件（可能是空文件夹或链接已失效）")
        val links = resolveLinks(cookie, shareId, stoken, entries.map { it.second.fid })
        val list = entries.mapNotNull { (path, e) ->
            val url = links[e.fid]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiskParser.FileInfo(path, url, e.size)
        }
        if (list.isEmpty()) error("文件列表已读取，但直链全部获取失败（多为 Cookie 失效或被风控，请在浏览器重新登录夸克后再试）")
        writeCache(ctx, shareId, list)
        return list
    }

    private data class Entry(val fid: String, val name: String, val size: Long)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36"

    /** 深度优先收集目录树，文件名前缀带上级目录，下载下来能看出归属 */
    private fun collectTree(cookie: String, shareId: String, stoken: String, pdirFid: String, prefix: String, out: MutableList<Pair<String, Entry>>, depth: Int = 0) {
        if (depth > 6) return
        var page = 1
        var hasMore = true
        while (hasMore) {
            val detailApi = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=$shareId&stoken=$stoken&pdir_fid=$pdirFid&force=0&_page=$page&_size=50"
            val req = Request.Builder().url(detailApi)
                .header("Cookie", cookie)
                .header("Referer", "https://pan.quark.cn/")
                .header("Origin", "https://pan.quark.cn")
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val json = JSONObject(client.newCall(req).execute().use { it.body?.string() ?: "" })
            if (json.optInt("code", 0) != 0 && json.optJSONObject("data") == null) {
                if (out.isNotEmpty()) break
                error("读取分享目录失败（${json.optInt("code")}）：${json.optString("message")}")
            }
            val data = json.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val f = list.getJSONObject(i)
                val fid = f.optString("fid")
                val name = f.optString("file_name").ifBlank { "未命名" }
                if (f.optBoolean("dir")) {
                    collectTree(cookie, shareId, stoken, fid, "$prefix$name/", out, depth + 1)
                } else {
                    out.add(prefix + name to Entry(fid, name, f.optLong("size", 0L)))
                }
            }
            if (list.length() < 50) hasMore = false else page++
        }
    }

    /** 批量取直链：一次最多 30 个 fid，失败再单个兜底，比逐个请求快一个数量级 */
    private fun resolveLinks(cookie: String, shareId: String, stoken: String, fids: List<String>): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        val apis = listOf(
            "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=$shareId&stoken=$stoken",
            "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc"
        )
        fun one(api: String, ids: List<String>): Boolean {
            val body = """{"fids":[${ids.joinToString(",") { "\"$it\"" }}],"pwd_id":"$shareId","stoken":"$stoken"}"""
            val req = Request.Builder().url(api).post(body.toRequestBody("application/json".toMediaType()))
                .header("Cookie", cookie).header("Referer", "https://pan.quark.cn/")
                .header("Origin", "https://pan.quark.cn").header("User-Agent", UA)
                .build()
            val txt = runCatching { client.newCall(req).execute().use { it.body?.string() ?: "" } }.getOrDefault("")
            val json = runCatching { JSONObject(txt) }.getOrNull() ?: return false
            val arr = json.optJSONArray("data") ?: return false
            var got = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("download_url")
                if (url.isNotBlank()) {
                    val fid = o.optString("fid")
                    if (fid.isNotBlank()) map[fid] = url else if (i < ids.size) map[ids[i]] = url
                    got++
                }
            }
            return got > 0
        }
        for (chunk in fids.chunked(30)) {
            var done = false
            for (api in apis) { if (one(api, chunk)) { done = true; break } }
            if (!done) {
                // 批量不支持时逐个来，保证不丢文件
                chunk.forEach { fid ->
                    if (map[fid] == null) {
                        for (api in apis) { if (one(api, listOf(fid))) break }
                    }
                }
            }
        }
        return map
    }

    // ---- 解析结果缓存（15 分钟内重复解析秒开） ----
    private fun cacheFile(ctx: Context, shareId: String) = java.io.File(ctx.cacheDir, "quark_$shareId.json")

    private fun readCache(ctx: Context, shareId: String): List<DiskParser.FileInfo>? = runCatching {
        val f = cacheFile(ctx, shareId)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 15 * 60_000L) return null
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DiskParser.FileInfo(o.optString("n"), o.optString("u"), o.optLong("s", 0L))
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun writeCache(ctx: Context, shareId: String, list: List<DiskParser.FileInfo>) = runCatching {
        val arr = org.json.JSONArray()
        list.forEach { val o = JSONObject(); o.put("n", it.name); o.put("u", it.url); o.put("s", it.size); arr.put(o) }
        cacheFile(ctx, shareId).writeText(arr.toString())
    }
}
