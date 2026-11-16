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
            val currentVersion = rawCurrentVersion.substringBefore("-").trim()
            val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=100").header("User-Agent", "LM-Downloader").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val jsonArray = JsonParser.parseString(resp.body?.string()).asJsonArray
                val isZeta = BuildConfig.CHANNEL == "zeta"
                val targetName = if (isZeta) "Zeta" else "Omega"
                var best: ReleaseInfo? = null
                var maxVer = currentVersion

                for (element in jsonArray) {
                    val json = element.asJsonObject
                    if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) continue
                    val tag = json.get("tag_name")?.asString ?: continue
                    val body = json.get("body")?.asString ?: ""
                    val rawTag = tag.removePrefix("v").substringBefore("-build").substringBefore("-zeta").substringBefore("-omega").trim()
                    val apkUrl = json.getAsJsonArray("assets").firstOrNull { 
                        val name = it.asJsonObject.get("name").asString
                        name.contains(targetName, true) && name.endsWith(".apk") 
                    }?.asJsonObject?.get("browser_download_url")?.asString

                    if (!apkUrl.isNullOrBlank() && isNewer(rawTag, maxVer)) {
                        maxVer = rawTag
                        best = ReleaseInfo(rawTag, apkUrl, body, isZeta)
                    }
                }
                return@withContext best
            }
        } catch (e: Exception) { null }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        return try {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val l = local.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                if (r.getOrElse(i) { 0 } > l.getOrElse(i) { 0 }) return true
                if (r.getOrElse(i) { 0 } < l.getOrElse(i) { 0 }) return false
            }
            false
        } catch (e: Exception) { false }
    }

    fun showUpdateDialog(ctx: Context, info: ReleaseInfo) {
        if (ThemeStore.getIgnoreVersion(ctx) == info.version) return
        AlertDialog.Builder(ctx)
            .setTitle("发现新版本 v${info.version} 🎉")
            .setMessage(info.changelog)
            .setPositiveButton("立即更新") { _, _ -> DownloadManager.add(ctx, info.downloadUrl, "Update_${info.version}.apk", 8, 0, emptyMap(), DirStore.resolveDownloadDir(ctx)) }
            .setNeutralButton("忽略此版本") { _, _ -> ThemeStore.setIgnoreVersion(ctx, info.version) }
            .setNegativeButton("稍后", null).show()
    }
}
