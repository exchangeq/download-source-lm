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

    const val CHROME_ANDROID_TABLET =
        "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val EDGE_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0"
    const val OPERA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 OPR/109.0.0.0"
    const val CHROME_MAC =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val WECHAT_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49.2600(0x28003133) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"
    const val BAIDU_APP =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36 baiduboxapp/13.30.0.0"

    /** 下载默认 UA：部分站点对非浏览器 UA 直接限速或 403 */
    const val DEFAULT = CHROME_ANDROID

    data class Preset(val key: String, val label: String, val ua: String)

    val PRESETS = listOf(
        Preset("android", "Chrome · 安卓", CHROME_ANDROID),
        Preset("ios", "Safari · iPhone", SAFARI_IOS),
        Preset("windows", "Chrome · Windows", CHROME_DESKTOP),
        Preset("edge", "Edge · Windows", EDGE_DESKTOP),
        Preset("firefox", "Firefox · Windows", FIREFOX_DESKTOP),
        Preset("tablet", "Chrome · 平板", CHROME_ANDROID_TABLET),
        Preset("edge_android", "Edge · 安卓", EDGE_ANDROID),
        Preset("opera", "Opera · Windows", OPERA_DESKTOP),
        Preset("mac", "Chrome · macOS", CHROME_MAC),
        Preset("wechat", "微信内置", WECHAT_ANDROID),
        Preset("baidu_app", "百度 App", BAIDU_APP)
    )

    fun resolve(key: String): String = PRESETS.firstOrNull { it.key == key }?.ua ?: CHROME_ANDROID
}
