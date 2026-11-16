package com.downloadsource.utils

/** 统一 UA 池：浏览器与下载共用，避免被 CDN 识别成异常客户端而限速 */
object UserAgents {
    const val CHROME_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    const val CHROME_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val EDGE_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
    const val SAFARI_IOS =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    const val FIREFOX_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"

    /** 下载默认 UA：部分站点对非浏览器 UA 直接限速或 403 */
    const val DEFAULT = CHROME_ANDROID

    data class Preset(val key: String, val label: String, val ua: String)

    val PRESETS = listOf(
        Preset("android", "Chrome · 安卓", CHROME_ANDROID),
        Preset("ios", "Safari · iPhone", SAFARI_IOS),
        Preset("windows", "Chrome · Windows", CHROME_DESKTOP),
        Preset("edge", "Edge · Windows", EDGE_DESKTOP),
        Preset("firefox", "Firefox · Windows", FIREFOX_DESKTOP)
    )

    fun resolve(key: String): String = PRESETS.firstOrNull { it.key == key }?.ua ?: CHROME_ANDROID
}
