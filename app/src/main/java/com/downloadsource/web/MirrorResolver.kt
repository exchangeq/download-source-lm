package com.downloadsource.web

import android.content.Context

object MirrorResolver {
    private val PREF = "mirrors"
    private val KEY_LIST = "list"

    val DEFAULT = listOf(
        "https://ghproxy.com/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://mirrors.tuna.tsinghua.edu.cn/github-release/", // 清华大学镜像
        "https://mirrors.aliyun.com/github-release/",           // 阿里云镜像
        "https://kkgithub.com/",
        ""
    )

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LIST, null)
        return if (raw.isNullOrBlank()) DEFAULT else raw.split('\n').filter { it.isNotBlank() || it == "" }
    }

    fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_LIST, list.joinToString("\n")).apply()
    }

    fun resolve(context: Context, url: String): String {
        if (!url.contains("github.com") && !url.contains("githubusercontent.com")) return url
        val mirrors = load(context).filter { it.isNotBlank() }
        return mirrors.firstOrNull()?.let { m ->
            if (m.endsWith("/")) "$m$url" else "$m/$url"
        } ?: url
    }
}
