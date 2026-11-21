package com.downloadsource.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object DirStore {
    private const val PREF = "ds_pref"
    private const val KEY_PATH = "download_path"

    fun save(context: Context, uri: Uri) {
        val path = treeUriToPath(uri)
        if (path != null) setCustomPath(context, path)
    }

    fun setCustomPath(context: Context, path: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PATH, path).apply()
    }

    /** 真正写一次探针，避免 Android 11+ canWrite() 撒谎导致文件写不进去/列表读不到 */
    fun reallyWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        val probe = File(dir, ".lm_probe")
        probe.writeText("1")
        probe.delete()
        true
    }.getOrDefault(false)

    fun resolveDownloadDir(context: Context): File {
        val savedPath = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PATH, null)
        if (savedPath != null && reallyWritable(File(savedPath))) return File(savedPath)
        return defaultDir(context)
    }

    /** 文件库要扫的全部候选目录：用户自选、公共下载、应用专属，历史版本可能落在不同位置 */
    fun candidateDirs(context: Context): List<File> {
        val out = mutableListOf<File>()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PATH, null)?.let { out.add(File(it)) }
        out.add(resolveDownloadDir(context))
        out.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DownloadSource"))
        context.getExternalFilesDir(null)?.let { out.add(File(it, "DownloadSource")) }
        out.add(File(context.filesDir, "DownloadSource"))
        return out.distinctBy { it.absolutePath }
    }

    /** 保险箱目录：全项目统一入口，避免移动端和列表端拿不同路径导致「移进去看不见」 */
    fun vaultDir(context: Context): File {
        val dir = File(resolveDownloadDir(context), ".vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // 修复：直接使用 App 的公共下载目录，彻底解决 Android 10+ 权限问题
    fun defaultDir(context: Context): File {
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicTarget = File(publicDownload, "DownloadSource")
        if (publicDownload.exists() && reallyWritable(publicTarget)) return publicTarget
        val f = File(context.getExternalFilesDir(null), "DownloadSource")
        if (!f.exists()) f.mkdirs()
        return f
    }

    private fun treeUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":").toTypedArray()
        if (split.size >= 2) {
            val type = split[0]; val path = split[1]
            if ("primary".equals(type, ignoreCase = true)) Environment.getExternalStorageDirectory().absolutePath + "/" + path
            else "/storage/$type/$path"
        } else null
    } catch (e: Exception) { null }
}
