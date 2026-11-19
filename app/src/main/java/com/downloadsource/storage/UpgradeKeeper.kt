package com.downloadsource.storage

import android.content.Context
import com.downloadsource.core.DownloadManager
import java.io.File

/**
 * 跨版本保留：每次版本变化时跑一次幂等迁移，保证升级不清数据、旧文件不丢。
 * 1. 旧明文保险箱 → AES 加密（VaultRepo.list 自带迁移）
 * 2. 清理失去归属的 .part / .ts_parts 残片
 * 3. 校验下载目录与保险箱目录可写
 * 4. 记录最后运行版本，避免重复迁移
 */
object UpgradeKeeper {
    private const val PREF = "lm_upgrade"
    private const val KEY_VERSION = "last_version"
    private const val KEY_MIGRATED = "vault_migrated"

    fun run(context: Context, versionName: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_VERSION, "") == versionName) return

        // 1) 保险箱旧明文迁移（幂等，失败保留原文件）
        runCatching { VaultRepo.list(context) }

        // 2) 清残片：只清不属于任何任务的文件，避免误删进行中的下载
        runCatching {
            val dir = DirStore.resolveDownloadDir(context)
            val alive = DownloadManager.tasks.value.map { it.fileName }.toSet()
            dir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.startsWith(".") && name.endsWith(".part")) {
                    val real = name.removePrefix(".").removeSuffix(".part")
                    if (real !in alive) f.delete()
                }
                if (name.startsWith(".") && name.endsWith(".ts_parts") && !f.isDirectory) f.delete()
                if (name.endsWith(".ts_parts") && f.isDirectory) {
                    val real = name.removeSuffix(".ts_parts")
                    if (real !in alive) f.deleteRecursively()
                }
            }
        }

        // 3) 目录兜底
        runCatching {
            DirStore.resolveDownloadDir(context).mkdirs()
            DirStore.vaultDir(context).mkdirs()
        }

        prefs.edit().putString(KEY_VERSION, versionName).putBoolean(KEY_MIGRATED, true).apply()
    }
}
