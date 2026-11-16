package com.downloadsource.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class UserScript(val id: String, val name: String, val code: String, val enabled: Boolean)

object TampermonkeyManager {
    private const val PREF = "tampermonkey_prefs"
    private const val KEY = "scripts"
    private val gson = Gson()

    fun getScripts(ctx: Context): List<UserScript> {
        val json = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<UserScript>>() {}.type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun saveScripts(ctx: Context, scripts: List<UserScript>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(scripts)).apply()
    }
}
