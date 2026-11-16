package com.downloadsource.storage

import android.content.Context
import java.net.URI

/** 按站点保存自定义 JS：host -> 脚本 */
object SiteScriptStore {
    private const val PREF = "site_scripts"

    fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("").removePrefix("www.")

    fun getAll(ctx: Context): Map<String, String> {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    fun get(ctx: Context, host: String): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(host, "") ?: ""

    fun put(ctx: Context, host: String, script: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(host, script).apply()
    }

    fun remove(ctx: Context, host: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(host).apply()
    }
}
