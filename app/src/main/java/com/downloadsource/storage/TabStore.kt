package com.downloadsource.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 标签页持久化：退出/被杀进程后仍能恢复上次打开的页面 */
object TabStore {
    private const val PREF = "lm_tabs"
    private const val KEY_TABS = "tabs_json"
    private const val KEY_ACTIVE = "active_id"

    data class SavedTab(val id: Int, val title: String, val url: String)

    private val gson = Gson()

    fun save(context: Context, tabs: List<SavedTab>, activeId: Int) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_TABS, gson.toJson(tabs.take(20)))
                .putInt(KEY_ACTIVE, activeId)
                .apply()
        }
    }

    fun load(context: Context): List<SavedTab> = runCatching {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TABS, null)
            ?: return emptyList()
        val type = object : TypeToken<List<SavedTab>>() {}.type
        gson.fromJson<List<SavedTab>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun activeId(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_ACTIVE, -1)
}
