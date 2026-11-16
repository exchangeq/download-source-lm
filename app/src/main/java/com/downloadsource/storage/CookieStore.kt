package com.downloadsource.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CookieStore {
    private const val PREF_NAME = "secure_cookie_store"

    private fun getPrefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREF_NAME,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(ctx: Context, platform: String, cookie: String) {
        getPrefs(ctx).edit().putString(platform, cookie).apply()
    }

    fun load(ctx: Context, platform: String): String? = getPrefs(ctx).getString(platform, null)
}
