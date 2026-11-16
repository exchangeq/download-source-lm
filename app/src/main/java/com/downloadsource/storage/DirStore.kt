package com.downloadsource.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object DirStore {
    private const val PREF = "ds_pref"
    private const val KEY_PATH = "download_path"

    // 兼容 MainActivity 的调用
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

    fun defaultDir(context: Context): File {
        val f = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DownloadSource")
        if (!f.exists()) f.mkdirs()
        return f
    }

    private fun treeUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":").toTypedArray()
        if (split.size >= 2) {
            val type = split[0]
            val path = split[1]
            if ("primary".equals(type, ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath + "/" + path
            } else {
                "/storage/$type/$path"
            }
        } else null
    } catch (e: Exception) { null }
}
