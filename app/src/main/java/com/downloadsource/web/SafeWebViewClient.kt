package com.downloadsource.web

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

open class SafeWebViewClient(
    private val onDownloadRequest: (String) -> Unit = {},
    private val onPlayM3u8: (String) -> Unit = {}
) : WebViewClient() {

    private val blockedSchemes = setOf("baiduboxapp", "baiduboxlite")
    private val downloadExtensions = listOf(".mp4", ".mkv", ".mp3", ".m3u8", ".zip", ".rar", ".apk", ".pdf", ".exe", ".txt", ".md", ".json", ".xml", ".csv")

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // 不再返回空 Body：统计脚本被截断会让整段 JS 抛错，很多站直接白屏。
        // 拦截交给浏览器的“广告拦截”开关，按站点脚本方式处理，不做资源层改写。
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val uri = req.url
        val scheme = uri.scheme?.lowercase()
        val url = uri.toString()

        if (scheme in blockedSchemes) return true

        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                view.context.startActivity(intent)
                return true
            } catch (e: Exception) { return true }
        }
        if (scheme == "mqq" || scheme == "weixin" || scheme == "alipays" || scheme == "taobao") {
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            } catch (e: Exception) { return true }
        }

        val cleanUrl = url.substringBefore("?")
        if (downloadExtensions.any { cleanUrl.endsWith(it, ignoreCase = true) }) {
            if (url.contains(".m3u8")) onPlayM3u8(url) else onDownloadRequest(url)
            return true 
        }

        if (scheme == "http" || scheme == "https") return false
        return try {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) { true }
    }
}
