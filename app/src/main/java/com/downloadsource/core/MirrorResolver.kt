package com.downloadsource.core

import android.content.Context

object MirrorResolver {
    private const val PREF = "mirrors"
    private const val KEY_LIST = "enabled_list"

    /** 仅这些前缀型代理能通过“拼接原地址”方式工作，其余镜像一律不参与改写 */
    private val PREFIX_PROXIES = listOf(
        "ghproxy.net", "gh-proxy.com", "gh-proxy.net", "ghfast.top",
        "ghps.cc", "ghproxy.cc", "gh.api.99988866.xyz", "ghproxy.com"
    )

    val AVAILABLE_MIRRORS = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghps.cc/",
        "https://ghproxy.cc/",
        "" // 直连（默认）
    )

    fun load(context: Context): List<String> {
        val set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_LIST, setOf("")) ?: setOf("")
        return set.toList()
    }

    fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_LIST, list.toSet()).apply()
    }

    /**
     * 默认直连；只有用户显式启用了前缀型代理才改写地址。
     * 不再无条件取第一个镜像，避免把死链或非代理型镜像拼进 URL。
     */
    fun resolveFastest(context: Context, url: String): String {
        if (!url.contains("github.com") && !url.contains("githubusercontent.com")) return url
        val enabled = load(context).filter { it.isNotBlank() }
        if (enabled.isEmpty()) return url
        val mirror = enabled.firstOrNull { m -> PREFIX_PROXIES.any { m.contains(it) } } ?: return url
        return if (mirror.endsWith("/")) "$mirror$url" else "$mirror/$url"
    }
}
