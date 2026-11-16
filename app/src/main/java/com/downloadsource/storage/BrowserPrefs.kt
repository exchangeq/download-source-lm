package com.downloadsource.storage

import android.content.Context

object BrowserPrefs {
    private const val PREF = "browser_prefs"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_SNIFFER = "sniffer_engine" // 0=JS注入, 1=Native底层

    fun getEngine(ctx: Context): String = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ENGINE, "Bing") ?: "Bing"
    fun setEngine(ctx: Context, engine: String) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine).apply() }

    // 新增引擎切换
    fun getSnifferType(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_SNIFFER, 0)
    fun setSnifferType(ctx: Context, type: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SNIFFER, type).apply() }
}
