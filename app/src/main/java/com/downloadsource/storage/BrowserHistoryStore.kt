package com.downloadsource.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object BrowserHistoryStore {
    private const val PREF = "browser_history"
    private const val KEY_SESSION = "last_session"
    private const val KEY_HISTORY = "history_list"
    private val gson = Gson()

    // 保存上次退出的网页
    fun saveSession(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SESSION, url).apply()
    }

    // 获取上次网页 (默认必应)
    fun getSession(ctx: Context): String {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SESSION, "https://www.bing.com") ?: "https://www.bing.com"
    }

    // 添加历史记录
    fun addHistory(ctx: Context, url: String) {
        if (url.startsWith("http") && !url.startsWith("http://localhost")) {
            val list = getHistory(ctx).toMutableList()
            list.remove(url) // 去除重复
            list.add(0, url) // 最新加在最前面
            if (list.size > 100) list.removeAt(list.size - 1) // 最多100条
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, gson.toJson(list)).apply()
        }
    }

    // 获取所有历史
    fun getHistory(ctx: Context): List<String> {
        val json = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
    
    fun clearHistory(ctx: Context) {
         ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }
}
