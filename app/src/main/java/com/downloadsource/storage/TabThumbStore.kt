package com.downloadsource.storage

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** 标签页缩略图：切换标签前截一张当前画面，卡片上就能看到内容预览 */
object TabThumbStore {
    private fun dir(context: Context) = File(context.cacheDir, "tab_thumbs").apply { mkdirs() }

    fun save(context: Context, tabId: Int, bitmap: Bitmap) {
        runCatching {
            val f = File(dir(context), "$tabId.jpg")
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }
    }

    fun get(context: Context, tabId: Int): File? = File(dir(context), "$tabId.jpg").takeIf { it.exists() && it.length() > 0 }

    fun clear(context: Context, tabId: Int) { runCatching { File(dir(context), "$tabId.jpg").delete() } }
}
