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

    fun blockedCount(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_COUNT, 0)

    fun resetCount(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_COUNT, 0).apply()
    }
}
