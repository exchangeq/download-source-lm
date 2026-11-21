package com.downloadsource.core

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.downloadsource.BuildConfig
import com.downloadsource.ThemeStore
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 更新下载服务（对齐 PocketForge 的做法）：
 *  1. releases/latest（失败退列表接口）；
 *  2. 下载到 cache/update，4 段 Range 并行，失败回退单流；
 *  3. 安装前校验：包名一致 + 签名摘要一致，任一不符直接拒绝；
 *  4. 只接受 https 资源。
 */
object UpdateManager {
    private const val REPO = "exchangeq/download-source-lm"
    private val client = OkHttpClient()

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String,
        val isZeta: Boolean,
        val size: Long = 0L,
        val htmlUrl: String = ""
    )

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

    private fun fetchJson(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "LM-Downloader")
            .header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    suspend fun checkUpdate(rawCurrentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val versions = mutableListOf<String>()
        fun parseOne(raw: String?): ReleaseInfo? {
            if (raw == null) return null
            val elems = runCatching {
                val root = JsonParser.parseString(raw)
                if (root.isJsonArray) root.asJsonArray.map { it.asJsonObject } else listOf(root.asJsonObject)
            }.getOrDefault(emptyList())
            val isBeta = BuildConfig.APPLICATION_ID.endsWith(".beta") || BuildConfig.CHANNEL == "zeta"
            var best: ReleaseInfo? = null
            var bestVer: String? = null
            for (json in elems) {
                if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) continue
                val tag = json.get("tag_name")?.asString ?: continue
                val body = json.get("body")?.asString ?: ""
                val version = tag.removePrefix("v").removeSuffix("-lm").removeSuffix("-LM").removeSuffix("-beta").trim()
                versions.add(version)
                val assets = json.getAsJsonArray("assets")?.map { it.asJsonObject } ?: emptyList()
                val apks = assets.filter { it.get("name")?.asString?.endsWith(".apk") == true }
                val branded = apks.filter { it.get("name")?.asString?.contains("LM-Ultra-Fast-Downloader", true) == true }
                val channel = branded.filter {
                    val n = it.get("name")?.asString?.lowercase() ?: ""
                    if (isBeta) n.contains("beta") else !n.contains("beta")
                }
                val apk = channel.firstOrNull() ?: branded.firstOrNull() ?: apks.firstOrNull()
                val apkUrl = apk?.get("browser_download_url")?.asString
                val size = apk?.get("size")?.asLong ?: 0L
                val html = json.get("html_url")?.asString ?: ""
                if (!apkUrl.isNullOrBlank() && (bestVer == null || isNewerVersion(version, bestVer))
                    && isNewerVersion(version, rawCurrentVersion)
                ) {
                    bestVer = version
                    best = ReleaseInfo(version, apkUrl, body, isBeta, size, html)
                }
            }
            return best
        }
        parseOne(fetchJson("https://api.github.com/repos/$REPO/releases/latest"))?.let { return@withContext it }
        parseOne(fetchJson("https://api.github.com/repos/$REPO/releases?per_page=100"))?.let { return@withContext it }
        null
    }

    // ---------- 下载（4 段并行，失败回退单流） ----------

    private fun downloadSingle(url: String, target: File, expected: Long, onProgress: (Int, String) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "LM-Downloader").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: expected
            resp.body?.byteStream()?.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n); read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct.coerceIn(0, 100), human(read, total)) }
                    }
                }
            }
        }
    }

    private fun downloadParallel(url: String, target: File, total: Long, onProgress: (Int, String) -> Unit): Boolean {
        if (total < 6L * 1024 * 1024) return false
        val parts = 4
        val chunk = total / parts
        val done = AtomicLong(0)
        val errors = AtomicInteger(0)
        val threads = (0 until parts).map { i ->
            val start = i * chunk
            val end = if (i == parts - 1) total - 1 else start + chunk - 1
            Thread {
                runCatching {
                    val req = Request.Builder().url(url).header("User-Agent", "LM-Downloader")
                        .header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity").build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code != 206) throw IllegalStateException("不支持分段：HTTP ${resp.code}")
                        val crStart = resp.header("Content-Range")?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull()
                        if (crStart != null && crStart != start) throw IllegalStateException("区间错位")
                        java.io.RandomAccessFile(target, "rw").use { raf ->
                            raf.seek(start)
                            resp.body?.byteStream()?.use { ins ->
                                val buf = ByteArray(64 * 1024)
                                var written = 0L
                                val want = end - start + 1
                                while (written < want) {
                                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), want - written).toInt())
                                    if (n <= 0) break
                                    raf.write(buf, 0, n); written += n
                                    val d = done.addAndGet(n.toLong())
                                    val pct = ((d * 100) / total).toInt()
                                    onProgress(pct.coerceIn(0, 100), human(d, total))
                                }
                                if (written < want) throw IllegalStateException("分片不完整")
                            }
                        }
                    }
                }.onFailure { errors.incrementAndGet() }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        return errors.get() == 0
    }

    private fun human(read: Long, total: Long): String {
        fun mb(v: Long) = String.format("%.1fMB", v / 1048576.0)
        return if (total > 0) "${mb(read)} / ${mb(total)}" else mb(read)
    }

    // ---------- 校验与安装 ----------

    private fun signatureDigest(info: android.content.pm.PackageInfo): String? {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        return bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
    }

    /** 返回 null 表示校验通过，否则返回拒绝原因 */
    fun verify(ctx: Context, file: File): String? {
        if (!file.exists() || file.length() <= 0) return "安装包为空"
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "不是有效的 APK"
        if (archive.packageName != ctx.packageName) return "包名不一致：${archive.packageName}，拒绝安装"
        val incoming = signatureDigest(archive) ?: return "读不到新包签名，拒绝安装"
        val installed = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, flags).let { signatureDigest(it) }
        }.getOrNull() ?: return "读不到当前包签名，拒绝安装"
        if (incoming != installed) return "签名不一致，拒绝安装（可能是被替换的包）"
        return null
    }

    fun install(ctx: Context, file: File): String {
        verify(ctx, file)?.let { return it }
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "已拉起系统安装器"
        } catch (t: Throwable) { "无法拉起安装器：${t.message}" }
    }

    fun showUpdateDialog(ctx: Context, info: ReleaseInfo) {
        if (ThemeStore.getIgnoreVersion(ctx) == info.version) return
        AlertDialog.Builder(ctx)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(info.changelog.ifBlank { "无更新说明" })
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(ctx, info) }
            .setNeutralButton("忽略此版本") { _, _ -> ThemeStore.setIgnoreVersion(ctx, info.version) }
            .setNegativeButton("稍后", null)
            .show()
    }

    fun downloadAndInstall(ctx: Context, info: ReleaseInfo) {
        if (!info.downloadUrl.startsWith("https://")) {
            android.widget.Toast.makeText(ctx, "更新地址不是 https，已拒绝", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "LM-${info.version}.apk")
        if (target.exists()) target.delete()
        val dlg = ProgressDialog(ctx).apply {
            setTitle("正在下载 v${info.version}")
            setMessage("准备中…")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            show()
        }
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching {
                val part = File(dir, "${info.version}.part")
                if (part.exists()) part.delete()
                val useParallel = downloadParallel(info.downloadUrl, part, info.size, { p, txt ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { dlg.progress = p; dlg.setMessage(txt) }
                })
                if (!useParallel) downloadSingle(info.downloadUrl, part, info.size) { p, txt ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { dlg.progress = p; dlg.setMessage(txt) }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) { part.copyTo(target, true); part.delete() }
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                dlg.dismiss()
                if (!ok) {
                    android.widget.Toast.makeText(ctx, "更新包下载失败，请稍后重试或到 Release 页手动下载", android.widget.Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val err = verify(ctx, target)
                if (err != null) {
                    android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                    target.delete()
                } else {
                    install(ctx, target)
                }
            }
        }
    }
}
