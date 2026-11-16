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

    suspend fun checkUpdate(rawCurrentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            // 强制剥离 -zeta, -omega 等后缀，只比对数字
            val currentVersion = rawCurrentVersion.substringBefore("-").trim()
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases?per_page=100")
                .header("User-Agent", "LM-Downloader")
                .build()
                
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val jsonArray = JsonParser.parseString(resp.body?.string()).asJsonArray
                val isZetaChannel = BuildConfig.CHANNEL == "zeta"
                val targetName = if (isZetaChannel) "Zeta" else "Omega"

                var bestRelease: ReleaseInfo? = null
                var maxVersion = currentVersion

                for (element in jsonArray) {
                    val json = element.asJsonObject
                    if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) continue

                    val tag = json.get("tag_name")?.asString ?: continue
                    val body = json.get("body")?.asString ?: "暂无更新日志"
                    val assets = json.getAsJsonArray("assets")
                    
                    val rawTag = tag.removePrefix("v")
                    val remoteVersionName = rawTag.substringBefore("-build").substringBefore("-zeta").substringBefore("-omega").trim()

                    val apkUrl = assets.firstOrNull { 
                        val name = it.asJsonObject.get("name").asString
                        name.contains(targetName, true) && name.endsWith(".apk") 
                    }?.asJsonObject?.get("browser_download_url")?.asString

                    if (!apkUrl.isNullOrBlank() && isVersionNewer(remoteVersionName, maxVersion)) {
                        maxVersion = remoteVersionName
                        bestRelease = ReleaseInfo(remoteVersionName, apkUrl, body, isZetaChannel)
                    }
                }
                return@withContext bestRelease
            }
        } catch (e: Exception) { null }
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        return try {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val l = local.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                val rv = r.getOrElse(i) { 0 }
                val lv = l.getOrElse(i) { 0 }
                if (rv > lv) return true
                if (rv < lv) return false
            }
            false
        } catch (e: Exception) { false }
    }

    fun showUpdateDialog(ctx: Context, info: ReleaseInfo) {
        if (ThemeStore.getIgnoreVersion(ctx) == info.version) return
        val channelText = if (info.isZeta) "内测版 (Zeta)" else "正式版 (Omega)"
        AlertDialog.Builder(ctx)
            .setTitle("发现新$channelText v${info.version} 🎉")
            .setMessage("【当前通道：$channelText】\n\n${info.changelog}")
            .setPositiveButton("立即更新") { _, _ -> 
                DownloadManager.add(ctx, info.downloadUrl, "DownloadSource_Update_v${info.version}.apk", 8, 0, emptyMap(), DirStore.resolveDownloadDir(ctx))
            }
            .setNeutralButton("忽略此版本") { _, _ -> ThemeStore.setIgnoreVersion(ctx, info.version) }
            .setNegativeButton("稍后", null).show()
    }
}
