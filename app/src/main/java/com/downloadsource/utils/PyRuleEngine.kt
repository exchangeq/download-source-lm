package com.downloadsource.utils
import android.content.Context
import java.util.regex.Pattern
object PyRuleEngine {
    private val mediaPatterns = mutableListOf<Pattern>()
    private val adPatterns = mutableListOf<Pattern>()
    fun init(context: Context) {
        try {
            val inputStream = context.assets.open("sniffer_engine.py")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            var line: String?; var mode = 0
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.startsWith("MEDIA_PATTERNS")) mode = 1
                else if (l.startsWith("AD_BLOCK_PATTERNS")) mode = 2
                else if (l.startsWith("]")) mode = 0
                else if (l.startsWith("r\"")) {
                    val regex = l.substringAfter("r\"").substringBefore("\"")
                    if (mode == 1) mediaPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
                    if (mode == 2) adPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
                }
            }
            reader.close()
        } catch (e: Exception) { }
    }
    private val safeHostKeywords = listOf(
        "gss0.baidu.com", "img.baidu.com", "hiphotos.baidu.com", "b.bdstatic.com", "ss.bdstatic.com",
        "qpic.cn", "sinaimg.cn", "zhimg.com", "alicdn.com", "byteimg.com", "pstatp.com",
        "xiaohongshu.com", "hdslb.com", "doubanio.com", "googleusercontent", "gstatic.com"
    )

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        // 白名单先判：这些 CDN 上既有图片也有广告脚本，宁可少拦也别把页面搞崩
        if (safeHostKeywords.any { lower.contains(it) && lower.containsAnyImageExt() }) return false
        return adPatterns.any { runCatching { it.matcher(lower).matches() }.getOrDefault(false) }
    }

    private fun String.containsAnyImageExt(): Boolean =
        contains(".jpg") || contains(".jpeg") || contains(".png") || contains(".gif") || contains(".webp") || contains(".svg")

    fun shouldSniff(url: String): Boolean =
        mediaPatterns.any { runCatching { it.matcher(url).matches() }.getOrDefault(false) }

    /** 引擎自检：返回 (媒体规则数, 广告规则数)，设置页可展示 */
    fun stats(): Pair<Int, Int> = mediaPatterns.size to adPatterns.size

    /** 热更新规则：把 assets 里的规则重新加载一次 */
    fun reload(context: Context) {
        mediaPatterns.clear(); adPatterns.clear(); init(context)
    }
}
