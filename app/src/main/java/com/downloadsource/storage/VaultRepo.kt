package com.downloadsource.storage

import android.content.Context
import com.downloadsource.security.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VaultItem(
    val id: String,
    val name: String,
    val size: Long,
    val addedAt: Long,
)

/**
 * 保险箱：磁盘上只存 AES-GCM 密文（<id>.lmv），真实文件名与元信息保存在 index.json。
 * 旧的明文文件会被自动迁移加密，不会丢文件。
 */
object VaultRepo {
    private const val INDEX = "index.json"

    fun dir(context: Context): File = DirStore.vaultDir(context)

    private fun indexPath(context: Context) = File(dir(context), INDEX)

    fun list(context: Context): List<VaultItem> {
        migrateLegacy(context)
        val arr = runCatching { JSONArray(indexPath(context).readText()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<VaultItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val name = o.optString("name")
            val enc = pathOf(context, id)
            if (!enc.exists()) continue
            out.add(VaultItem(id, name.ifBlank { "加密文件" }, enc.length(), o.optLong("addedAt", enc.lastModified())))
        }
        return out.sortedByDescending { it.addedAt }
    }

    fun pathOf(context: Context, id: String) = File(dir(context), "$id.lmv")

    fun import(context: Context, src: File, displayName: String? = null): Boolean {
        if (!src.exists() || src.length() <= 0) return false
        val id = "v${System.currentTimeMillis().toString(36)}${(1000..9999).random()}"
        val dest = pathOf(context, id)
        if (!VaultCrypto.encrypt(src, dest)) return false
        // 只有密文校验通过才删源文件
        val ok = dest.exists() && dest.length() > 16
        if (!ok) { runCatching { dest.delete() }; return false }
        addIndex(context, id, displayName ?: src.name, System.currentTimeMillis())
        runCatching { src.delete() }
        return true
    }

    /** 解密到目标文件（播放/导出） */
    fun decryptTo(context: Context, item: VaultItem, out: File): Boolean {
        out.parentFile?.mkdirs()
        return VaultCrypto.decrypt(pathOf(context, item.id), out)
    }

    fun decryptToCache(context: Context, item: VaultItem): File? {
        val safeName = item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = File(File(context.cacheDir, "vault_play"), "${item.id}_$safeName")
        if (out.exists() && out.length() > 0) return out
        return if (decryptTo(context, item, out)) out else null
    }

    fun rename(context: Context, item: VaultItem, newName: String) {
        val arr = runCatching { JSONArray(indexPath(context).readText()) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == item.id) o.put("name", newName)
        }
        runCatching { indexPath(context).writeText(arr.toString()) }
    }

    fun delete(context: Context, item: VaultItem): Boolean {
        runCatching { pathOf(context, item.id).delete() }
        removeIndex(context, item.id)
        return true
    }

    fun export(context: Context, item: VaultItem, destDir: File): File? {
        val dest = File(destDir, item.name)
        val target = if (dest.exists()) {
            val base = item.name.substringBeforeLast('.', item.name)
            val ext = item.name.substringAfterLast('.', "")
            File(destDir, if (ext.isEmpty()) "$base (1)" else "$base (1).$ext")
        } else dest
        return if (decryptTo(context, item, target)) target else null
    }

    // ---- index ----
    private fun addIndex(context: Context, id: String, name: String, addedAt: Long) {
        val arr = runCatching { JSONArray(indexPath(context).readText()) }.getOrNull() ?: JSONArray()
        arr.put(JSONObject().apply {
            put("id", id); put("name", name); put("addedAt", addedAt)
        })
        runCatching { indexPath(context).writeText(arr.toString()) }
    }

    private fun removeIndex(context: Context, id: String) {
        val arr = runCatching { JSONArray(indexPath(context).readText()) }.getOrNull() ?: return
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) next.put(o)
        }
        runCatching { indexPath(context).writeText(next.toString()) }
    }

    /** 老版本直接放在 .vault 里的是明文，这里逐个加密替换，失败就保留原文件 */
    private fun migrateLegacy(context: Context) {
        val files = dir(context).listFiles() ?: return
        files.filter { it.isFile && !it.name.endsWith(".lmv") && it.name != INDEX }.forEach { legacy ->
            val id = "m${System.currentTimeMillis().toString(36)}${(1000..9999).random()}"
            val dest = pathOf(context, id)
            if (VaultCrypto.encrypt(legacy, dest) && dest.length() > 16) {
                addIndex(context, id, legacy.name, legacy.lastModified())
                runCatching { legacy.delete() }
            }
        }
    }
}
