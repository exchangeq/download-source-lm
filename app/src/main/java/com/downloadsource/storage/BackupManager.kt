package com.downloadsource.storage

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复：把全部 SharedPreferences（设置/书签/主题/UA/站点脚本索引/任务列表）+ 保险箱索引
 * 打包成 zip。跨版本、跨设备都能还原；保险箱密文本身不打包（密钥在 Keystore，换了设备也解不开）。
 */
object BackupManager {
    private const val MANIFEST = "lm_backup.txt"

    fun export(context: Context): File? = runCatching<File> {
        val out = File(DirStore.resolveDownloadDir(context), "LM-Backup-${System.currentTimeMillis()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write("LM Backup v1\npackage=${context.packageName}\n".toByteArray())
            zip.closeEntry()

            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { f ->
                zip.putNextEntry(ZipEntry("shared_prefs/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val index = File(DirStore.vaultDir(context), "index.json")
            if (index.exists()) {
                zip.putNextEntry(ZipEntry("vault/index.json"))
                index.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        out
    }.getOrNull()

    /** 还原：写回 shared_prefs 与保险箱索引，需重启应用生效 */
    fun restore(context: Context, zipFile: File): Boolean = runCatching {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val vaultDir = DirStore.vaultDir(context)
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                when {
                    name.startsWith("shared_prefs/") && name.endsWith(".xml") -> {
                        File(prefsDir, name.removePrefix("shared_prefs/")).outputStream().use { zin.copyTo(it) }
                        count++
                    }
                    name == "vault/index.json" -> {
                        File(vaultDir, "index.json").outputStream().use { zin.copyTo(it) }
                        count++
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
            count > 0
        }
    }.getOrDefault(false)
}
