package com.downloadsource.core

import java.net.URLEncoder

enum class SearchEngine(
    val label: String,
    val home: String,
    private val prefix: String
) {
    BING("Bing", "https://www.bing.com", "https://www.bing.com/search?q="),
    BAIDU("百度", "https://m.baidu.com", "https://m.baidu.com/s?word="),
    GOOGLE("Google", "https://www.google.com", "https://www.google.com/search?q=");

    fun search(keyword: String): String =
        prefix + URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")

    companion object {
        fun of(name: String?): SearchEngine =
            entries.firstOrNull { it.name.equals(name, true) || it.label == name } ?: BING

        /** 地址栏输入归一化：协议 / 域名 / IP / 关键词四类分流 */
        fun normalize(raw: String, engineName: String?): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""

            listOf("http://", "https://", "file://", "content://", "about:")
                .firstOrNull { s.startsWith(it, true) }?.let { return s }

            val head = s.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostLike = head.matches(Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9]{2,}(:[0-9]+)?$")) ||
                head.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]+)?$"))
            if (hostLike) return "https://$s"

            return of(engineName).search(s)
        }
    }
}
