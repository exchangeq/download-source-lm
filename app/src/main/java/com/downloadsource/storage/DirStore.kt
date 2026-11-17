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

    fun resolveDownloadDir(context: Context): File {
        val savedPath = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PATH, null)
        if (savedPath != null) {
            val f = File(savedPath)
            if (f.exists() && f.canWrite()) return f
        }
        return defaultDir(context)
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
        if (publicDownload.exists() && publicDownload.canWrite()) {
            val f = File(publicDownload, "DownloadSource")
            if (!f.exists()) f.mkdirs()
            return f
        }
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
