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

    // 桌面版 UA 开关
    fun isDesktop(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("desktop_mode", false)
    fun setDesktop(ctx: Context, value: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("desktop_mode", value).apply() }

    // 文字缩放
    fun getZoom(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("text_zoom", 100)
    fun setZoom(ctx: Context, value: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("text_zoom", value).apply() }

    fun getUaKey(ctx: Context): String = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("ua_key", "android") ?: "android"
    fun setUaKey(ctx: Context, key: String) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("ua_key", key).apply() }
}
