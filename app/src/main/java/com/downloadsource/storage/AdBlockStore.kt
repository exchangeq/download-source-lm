package com.downloadsource.storage

import android.content.Context

/** 网页广告拦截开关 + 计数（默认开启，但可在菜单/设置里随时关掉） */
object AdBlockStore {
    private const val PREF = "lm_adblock"
    private const val KEY_ON = "enabled"
    private const val KEY_COUNT = "blocked_count"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    fun addBlocked(context: Context, n: Int = 1) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putLong(KEY_COUNT, sp.getLong(KEY_COUNT, 0) + n).apply()
    }

    /** 站点白名单：某些站点主体内容托管在被规则命中的域名上，拦了就是黑屏/白屏 */
    fun isHostExempt(context: Context, host: String): Boolean =
        host.isNotBlank() && hostExempts(context).contains(host.lowercase())

    fun hostExempts(context: Context): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet("host_exempt", emptySet()) ?: emptySet()

    fun addHostExempt(context: Context, host: String) {
        if (host.isBlank()) return
        val set = hostExempts(context).toMutableSet()
        set.add(host.lowercase())
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet("host_exempt", set).apply()
    }

    fun removeHostExempt(context: Context, host: String) {
        val set = hostExempts(context).toMutableSet()
        set.remove(host.lowercase())
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet("host_exempt", set).apply()
    }

    fun blockedCount(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_COUNT, 0)

    fun resetCount(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_COUNT, 0).apply()
    }
}
