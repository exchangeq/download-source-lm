package com.downloadsource.utils

import android.content.Context
import com.downloadsource.storage.BrowserPrefs
import java.net.URLEncoder

enum class SearchEngine(val displayName: String, val baseUrl: String, val homeUrl: String, val region: String = "国际") {
    BAIDU("Baidu", "https://m.baidu.com/s?word=", "https://m.baidu.com", "国内"),
    BING("Bing", "https://www.bing.com/search?q=", "https://cn.bing.com", "国内"),
    SOGOU("Sogou", "https://www.sogou.com/web?query=", "https://www.sogou.com", "国内"),
    Q360("360搜索", "https://m.so.com/s?q=", "https://m.so.com", "国内"),
    SHENMA("神马", "https://m.sm.cn/s?q=", "https://m.sm.cn", "国内"),
    GOOGLE("Google", "https://www.google.com/search?q=", "https://www.google.com", "国际"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com", "国际"),
    YANDEX("Yandex", "https://yandex.com/search/?text=", "https://yandex.com", "国际"),
    BRAVE("Brave", "https://search.brave.com/search?q=", "https://search.brave.com", "国际"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=", "https://www.startpage.com", "国际");

    val host: String get() = homeUrl.substringAfter("://").substringBefore('/')

    fun search(keyword: String): String =
        baseUrl + URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")

    companion object {
        fun getCurrent(ctx: Context): SearchEngine {
            val name = BrowserPrefs.getEngine(ctx)
            return entries.firstOrNull { it.displayName.equals(name, true) || it.name.equals(name, true) } ?: BAIDU
        }

        /**
         * 地址栏输入归一化：完整协议 / 域名 / IP / 关键词四类分流。
         * 之前用 contains(".com") 判断，遇到 "kotlin.compose" 这类词会当成域名，直接打不开。
         */
        fun normalize(ctx: Context, raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""

            listOf("http://", "https://", "file://", "content://", "about:", "data:").firstOrNull { s.startsWith(it, true) }?.let { return s }

            val head = s.substringBefore('/').substringBefore('?').substringBefore('#')
            val isHost = head.matches(Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9]{2,}(:[0-9]+)?$")) ||
                head.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]+)?$")) ||
                head.equals("localhost", true) || head.startsWith("localhost:")
            if (isHost) return "https://$s"

            return getCurrent(ctx).search(s)
        }
    }
}
