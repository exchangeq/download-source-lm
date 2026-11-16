package com.downloadsource.storage

import android.content.Context

object BookmarkStore {
    private const val PREF = "bookmarks"
    private const val KEY = "urls"

    fun get(ctx: Context): List<String> {
        val set = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()
        return set.toList()
    }

    fun add(ctx: Context, url: String) {
        val set = get(ctx).toMutableSet()
        set.add(url)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }

    fun remove(ctx: Context, url: String) {
        val set = get(ctx).toMutableSet()
        set.remove(url)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }
}
