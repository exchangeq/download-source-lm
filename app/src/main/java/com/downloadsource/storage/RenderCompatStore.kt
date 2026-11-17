package com.downloadsource.storage

import android.content.Context

/** 记录哪些站点需要软件渲染（GPU 合成黑屏的兜底开关） */
object RenderCompatStore {
    private const val PREF = "lm_render_compat"
    private const val KEY = "software_hosts"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hosts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isSoftware(context: Context, host: String): Boolean =
        host.isNotBlank() && hosts(context).contains(host)

    fun setSoftware(context: Context, host: String, enabled: Boolean) {
        if (host.isBlank()) return
        val current = hosts(context).toMutableSet()
        if (enabled) current.add(host) else current.remove(host)
        prefs(context).edit().putStringSet(KEY, current).apply()
    }

    fun toggle(context: Context, host: String): Boolean {
        val next = !isSoftware(context, host)
        setSoftware(context, host, next)
        return next
    }
}
