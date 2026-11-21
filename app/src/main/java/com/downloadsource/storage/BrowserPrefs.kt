package com.downloadsource.storage

import android.content.Context

object BrowserPrefs {
    private const val PREF = "browser_prefs"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_SNIFFER = "sniffer_engine" // 0=JS注入, 1=Native底层

    fun getEngine(ctx: Context): String = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ENGINE, "Baidu") ?: "Baidu"
    fun setEngine(ctx: Context, engine: String) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine).apply() }

    // 浏览器界面样式：0 紧凑 / 1 圆润 / 2 极简
    fun getBrowserUi(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("browser_ui", 0)
    fun setBrowserUi(ctx: Context, value: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("browser_ui", value).apply() }

    // 新增引擎切换
    fun getSnifferType(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_SNIFFER, 0)
    fun setSnifferType(ctx: Context, type: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SNIFFER, type).apply() }

    // 桌面版 UA 开关
    fun isDesktop(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("desktop_mode", false)
    fun setDesktop(ctx: Context, value: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("desktop_mode", value).apply() }

    // 文字缩放
    fun getZoom(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("text_zoom", 100)
    fun setZoom(ctx: Context, value: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("text_zoom", value).apply() }

    /** 浏览器界面样式：0 标准 / 1 紧凑 / 2 悬浮 */
    fun getUiStyle(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("ui_style", 0)
    fun setUiStyle(ctx: Context, value: Int) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("ui_style", value).apply() }

    /** 拦截跳转到百度（默认关闭；某些站点会强制跳 baidu.com） */
    fun blockBaidu(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("block_baidu", false)
    fun setBlockBaidu(ctx: Context, value: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("block_baidu", value).apply() }

    fun getUaKey(ctx: Context): String = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("ua_key", "android") ?: "android"
    fun setUaKey(ctx: Context, key: String) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("ua_key", key).apply() }

    /** 无图模式：省流量、弱网提速（默认关，加载图片） */
    fun isImagesEnabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("load_images", true)
    fun setImagesEnabled(ctx: Context, value: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("load_images", value).apply() }

    /** 网页强制深色：白天护眼/夜间阅读（默认关，避免整页刷黑） */
    fun isWebDark(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("web_dark", false)
    fun setWebDark(ctx: Context, value: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("web_dark", value).apply() }
}
