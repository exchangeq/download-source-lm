package com.downloadsource.core

import android.app.AlertDialog
import android.content.Context
import com.downloadsource.BuildConfig
import com.downloadsource.ThemeStore
import com.downloadsource.storage.DirStore
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object UpdateManager {
    private const val REPO = "exchangeq/download-source-lm"
    private val client = OkHttpClient()

    data class ReleaseInfo(val version: String, val downloadUrl: String, val changelog: String, val isZeta: Boolean)

    /** 版本解析：10.0 / 10.0-kappa1.0 / 9.3.0-beta2.1 */
    private data class Ver(val nums: List<Int>, val stage: String, val stageNo: Int, val rev: Int)

    private fun parseVer(raw: String): Ver {
        val cleaned = raw.trim().removePrefix("v").substringBefore("+")
        val m = Regex("^(\\d+(?:\\.\\d+)*)(?:-([A-Za-z]+)(\\d*)(?:\\.(\\d+))?)?").find(cleaned)
        val numsStr = m?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "0"
        return Ver(
            nums = numsStr.split(".").map { it.toIntOrNull() ?: 0 },
            stage = (m?.groupValues?.get(2) ?: "").lowercase(),
            stageNo = (m?.groupValues?.get(3) ?: "").toIntOrNull() ?: 0,
            rev = (m?.groupValues?.get(4) ?: "").toIntOrNull() ?: 0
        )
    }

    /** 完整版本比较：数字段 → 代号（无代号 < 有代号）→ 代号序号 → 修订号 */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = parseVer(remote); val l = parseVer(local)
        val n = maxOf(r.nums.size, l.nums.size)
        for (i in 0 until n) {
            val rv = r.nums.getOrElse(i) { 0 }; val lv = l.nums.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        val rankR = if (r.stage.isEmpty()) 0 else 1
        val rankL = if (l.stage.isEmpty()) 0 else 1
        if (rankR != rankL) return rankR > rankL
        if (r.stage != l.stage) return r.stage > l.stage
        if (r.stageNo != l.stageNo) return r.stageNo > l.stageNo
        return r.rev > l.rev
    }

    suspend fun checkUpdate(rawCurrentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        // 列表接口被限流时退到 latest，避免「检测没反应」
        runCatching { fetchReleases() }.getOrNull()?.let { list ->
            pick(list, rawCurrentVersion)?.let { return@withContext it }
        }
        runCatching { fetchLatest() }.getOrNull()?.let { arr ->
            pick(arr, rawCurrentVersion)?.let { return@withContext it }
        }
        return@withContext null
    }

    private fun fetchReleases(): List<com.google.gson.JsonObject>? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=100")
            .header("User-Agent", "LM-Downloader").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return JsonParser.parseString(resp.body?.string()).asJsonArray.map { it.asJsonObject }
        }
    }

    private fun fetchLatest(): List<com.google.gson.JsonObject>? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("User-Agent", "LM-Downloader").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return listOf(JsonParser.parseString(resp.body?.string()).asJsonObject)
        }
    }

    private fun pick(els: List<com.google.gson.JsonObject>, rawCurrentVersion: String): ReleaseInfo? {
        try {
            val currentVersion = rawCurrentVersion.trim()
            // 通道识别按 applicationId 判断，比看资产名字更可靠
                val isBeta = BuildConfig.APPLICATION_ID.endsWith(".beta") || BuildConfig.CHANNEL == "zeta"
                var best: ReleaseInfo? = null
                var bestVer: String? = null

                for (json in els) {
                    if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) continue
                    val tag = json.get("tag_name")?.asString ?: continue
                    val body = json.get("body")?.asString ?: ""
                    val version = tag.removePrefix("v").removeSuffix("-lm").removeSuffix("-LM")
                        .removeSuffix("-beta").trim()
                    val assets = json.getAsJsonArray("assets")?.map { it.asJsonObject } ?: emptyList()
                    val apks = assets.filter { it.get("name")?.asString?.endsWith(".apk") == true }

                    // 优先选「品牌名 + 版本号」的成品包，排除 app-omega-release 这类内部名
                    val branded = apks.filter { it.get("name")?.asString?.contains("LM-Ultra-Fast-Downloader", true) == true }
                    val channel = branded.filter {
                        val n = it.get("name")?.asString?.lowercase() ?: ""
                        if (isBeta) n.contains("beta") else !n.contains("beta")
                    }
                    val apk = channel.firstOrNull() ?: branded.firstOrNull() ?: apks.firstOrNull()
                    val apkUrl = apk?.get("browser_download_url")?.asString

                    if (!apkUrl.isNullOrBlank() && (bestVer == null || isNewerVersion(version, bestVer))
                        && isNewerVersion(version, currentVersion)
                    ) {
                        bestVer = version
                        best = ReleaseInfo(version, apkUrl, body, isBeta)
                    }
                }
            return best
        } catch (e: Exception) { null }
    }

    fun showUpdateDialog(ctx: Context, info: ReleaseInfo) {
        if (ThemeStore.getIgnoreVersion(ctx) == info.version) return
        AlertDialog.Builder(ctx)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(info.changelog)
            .setPositiveButton("立即更新") { _, _ -> DownloadManager.add(ctx, info.downloadUrl, "Update_${info.version}.apk", 16, 0, emptyMap(), DirStore.resolveDownloadDir(ctx)) }
            .setNeutralButton("忽略此版本") { _, _ -> ThemeStore.setIgnoreVersion(ctx, info.version) }
            .setNegativeButton("稍后", null).show()
    }
}
