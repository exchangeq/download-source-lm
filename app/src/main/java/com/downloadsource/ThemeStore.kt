package com.downloadsource

import android.content.Context
import com.downloadsource.utils.PasswordCrypto

object ThemeStore {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private const val PREF = "theme_store"
    private const val KEY_MODE = "mode"
    private const val KEY_STYLE = "style"
    private const val KEY_IGNORE_VER = "ignore_version"
    private const val KEY_LOCK_PWD_HASH = "lock_pwd_hash"
    private const val KEY_LAST_UNLOCK_TIME = "last_unlock_time" // 新增：上次解锁时间

    fun get(context: Context): Int = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MODE, FOLLOW_SYSTEM)
    fun set(context: Context, value: Int) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, value).apply() }

    fun getThemeStyle(context: Context): Int = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_STYLE, 0)
    fun setThemeStyle(context: Context, value: Int) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_STYLE, value).apply() }

    fun getIgnoreVersion(context: Context): String? = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_IGNORE_VER, null)
    fun setIgnoreVersion(context: Context, version: String) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_IGNORE_VER, version).apply() }

    fun getLockPassword(context: Context): String? = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LOCK_PWD_HASH, null)
    fun setLockPassword(context: Context, password: String?) {
        val hash = if (password != null) PasswordCrypto.hashPassword(password) else null
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_LOCK_PWD_HASH, hash).apply()
        // 设置密码时，顺便重置时间戳，防止立即要求输入
        if (password != null) setLastUnlockTime(context, System.currentTimeMillis())
    }
    
    fun verifyLockPassword(context: Context, input: String): Boolean {
        val isCorrect = PasswordCrypto.verifyPassword(input, getLockPassword(context))
        if (isCorrect) setLastUnlockTime(context, System.currentTimeMillis())
        return isCorrect
    }

    // 新增：读写上次解锁时间
    fun getLastUnlockTime(context: Context): Long = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_UNLOCK_TIME, 0L)
    fun setLastUnlockTime(context: Context, time: Long) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_UNLOCK_TIME, time).apply() }
}
