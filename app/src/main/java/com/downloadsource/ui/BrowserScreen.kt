package com.downloadsource.ui

import kotlinx.coroutines.launch

import com.downloadsource.storage.RenderCompatStore

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.downloadsource.core.DownloadManager
import com.downloadsource.storage.BrowserHistoryStore
import com.downloadsource.storage.BrowserPrefs
import com.downloadsource.storage.DirStore
import com.downloadsource.storage.SiteScriptStore
import com.downloadsource.utils.PyRuleEngine
import com.downloadsource.utils.SearchEngine
import com.downloadsource.utils.UserAgents
import com.downloadsource.web.SafeWebViewClient
import com.downloadsource.web.WebInterceptor
import com.downloadsource.web.WebScripts
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private data class BrowserTab(val id: Int, var title: String, var url: String, var scrollY: Int = 0)
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    onPlayVideo: (File) -> Unit,
    onPlayStream: (String) -> Unit,
    externalUrlToLoad: String?,
    onUrlLoaded: () -> Unit
) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current

    val sessionUrl = remember { BrowserHistoryStore.getSession(ctx) }
    val restored = remember { com.downloadsource.storage.TabStore.load(ctx) }
    val tabs = remember {
        val list = if (restored.isNotEmpty()) {
            restored.map { BrowserTab(it.id, it.title, it.url.ifBlank { "about:blank" }, it.scrollY) }
        } else {
            listOf(BrowserTab(1, "新标签页", sessionUrl))
        }
        mutableListOf<BrowserTab>().apply { addAll(list) }.toMutableStateList()
    }
    val restoredActive = remember { com.downloadsource.storage.TabStore.activeId(ctx) }
    var activeId by remember { mutableStateOf(tabs.firstOrNull { it.id == restoredActive }?.id ?: tabs.first().id) }
    val idGen = remember { AtomicInteger((tabs.maxOfOrNull { it.id } ?: 1) + 1) }
    var linkMenuUrl by remember { mutableStateOf<String?>(null) }
    var tabMenuId by remember { mutableStateOf(-1) }

    var urlInput by remember { mutableStateOf(sessionUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }
    var pageLoading by remember { mutableStateOf(false) }

    var pendingDownloadUrl by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSniffList by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showSiteScript by remember { mutableStateOf(false) }
    var siteScriptText by remember { mutableStateOf("") }
    var showDataPanel by remember { mutableStateOf(false) }
    var readerMode by remember { mutableStateOf(false) }
    var forceDarkWeb by remember { mutableStateOf(false) }
    var incognito by remember { mutableStateOf(BrowserPrefs.isIncognito(ctx)) }
    var rebuildTick by remember { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var imagesOn by remember { mutableStateOf(BrowserPrefs.isImagesEnabled(ctx)) }
    var thirdPartyCookies by remember { mutableStateOf(true) }

    var engine by remember { mutableStateOf(SearchEngine.getCurrent(ctx)) }
    var desktopMode by remember { mutableStateOf(BrowserPrefs.isDesktop(ctx)) }
    var zoom by remember { mutableStateOf(BrowserPrefs.getZoom(ctx)) }
    var uaKey by remember { mutableStateOf(BrowserPrefs.getUaKey(ctx)) }
    var showUa by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    var pageError by remember { mutableStateOf<String?>(null) }
    val uiStyle = remember { BrowserPrefs.getUiStyle(ctx) }
    var blockBaidu by remember { mutableStateOf(BrowserPrefs.blockBaidu(ctx)) }
    var adBlockOn by remember { mutableStateOf(com.downloadsource.storage.AdBlockStore.isEnabled(ctx)) }
    var blankPage by remember { mutableStateOf(true) }
    var autoReloadCount by remember { mutableIntStateOf(0) }
    var ttsSpeaking by remember { mutableStateOf(false) }
    val tts = remember {
        var engineRef: android.speech.tts.TextToSpeech? = null
        runCatching {
            engineRef = android.speech.tts.TextToSpeech(ctx) { }
        }
        engineRef
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { tts?.stop(); tts?.shutdown() } }
    }
    var lastBlankWatchUrl by remember { mutableStateOf("") }
    val autoFitDone = remember { mutableSetOf<String>() }
    var adBlockedCount by remember { mutableStateOf(com.downloadsource.storage.AdBlockStore.blockedCount(ctx)) }
    var lastHost by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var browserUi by remember { mutableIntStateOf(BrowserPrefs.getBrowserUi(ctx)) }
    var showEnginePicker by remember { mutableStateOf(false) }
    val sniffedUrls = remember { mutableStateListOf<WebInterceptor.SniffedMedia>() }
    val probeMap = remember { mutableStateMapOf<String, com.downloadsource.core.MediaProbe.Info>() }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var pendingCustomName by remember { mutableStateOf("") }
    var renamingUrl by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val webInterceptor = remember { WebInterceptor() }
    val currentTab = { tabs.firstOrNull { it.id == activeId } }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    val webView = remember(rebuildTick) {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = BrowserPrefs.isImagesEnabled(ctx)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = if (BrowserPrefs.isDesktop(ctx)) DESKTOP_UA else UserAgents.resolve(BrowserPrefs.getUaKey(ctx))
            settings.textZoom = BrowserPrefs.getZoom(ctx).coerceIn(85, 130)
            settings.blockNetworkImage = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // 白屏常见根因：window.open / target=_blank 无人接管、混合内容被拦、媒体被手势限制
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(true)
            // 默认不改写网页配色：强深色会把部分站点（如百度）整页刷黑
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                settings.forceDark = if (BrowserPrefs.isWebDark(ctx)) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
            // 视口与白底：未加载完时也是白页而不是黑块
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.setNeedInitialFocus(false)
            settings.setSupportMultipleWindows(true)
            // 先把渲染优先级拉高：
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            val bootHost = SiteScriptStore.hostOf(urlInput)
            if (RenderCompatStore.isSoftware(ctx, bootHost)) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    val doGo = {
        val target = SearchEngine.normalize(ctx, urlInput)
        if (target.isNotBlank()) {
            urlInput = target
            (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(webView.windowToken, 0)
            webView.requestFocus()
            webView.loadUrl(target)
        }
    }


    fun syncTab(url: String?) {
        val tab = currentTab() ?: return
        if (url != null && url.isNotBlank()) tab.url = url
    }

    DisposableEffect(rebuildTick) {
        webView.addJavascriptInterface(webInterceptor, "AndroidBridge")
        webView.webViewClient = object : SafeWebViewClient(
            onDownloadRequest = { url -> pendingDownloadUrl = url; showDownloadDialog = true },
            onPlayM3u8 = { url -> onPlayStream(url) }
        ) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                blankPage = url.isNullOrBlank() || url.startsWith("about:")
                pageLoading = true
                pageError = null
                if (url != null && blockBaidu) {
                    val host = SiteScriptStore.hostOf(url)
                    if (host.endsWith("baidu.com") && !lastHost.endsWith("baidu.com")) {
                        view?.stopLoading()
                        android.widget.Toast.makeText(ctx, "已拦截跳转到百度", android.widget.Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                if (url != null && url != "about:blank") {
                    if (!addressFocused) urlInput = url
                    syncTab(url)
                    BrowserHistoryStore.saveSession(ctx, url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoading = false
                progress = 100
                if (url != null) {
                    syncTab(url)
                    if (!incognito) runCatching {
                        com.downloadsource.storage.TabStore.save(
                            ctx,
                            tabs.map { com.downloadsource.storage.TabStore.SavedTab(it.id, it.title, it.url, it.scrollY) },
                            activeId
                        )
                    }
                    lastHost = SiteScriptStore.hostOf(url)
                    if (!addressFocused) urlInput = url
                    if (!incognito && !url.startsWith("about:")) BrowserHistoryStore.addHistory(ctx, url)
                    runCatching {
                        val host = SiteScriptStore.hostOf(url)
                        val platform = when {
                            host.contains("quark.cn") -> "quark"
                            host.contains("baidu.com") -> "baidu"
                            host.contains("aliyundrive.com") || host.contains("alipan.com") -> "aliyun"
                            host.contains("115.com") -> "115"
                            host.contains("123pan") -> "123pan"
                            else -> ""
                        }
                        if (platform.isNotBlank()) {
                            val c = android.webkit.CookieManager.getInstance().getCookie(url)
                            if (!c.isNullOrBlank()) com.downloadsource.storage.CookieStore.save(ctx, platform, c)
                        }
                    }
                }
                view?.evaluateJavascript(webInterceptor.injectionScript, null)
                val sy = currentTab()?.scrollY ?: 0
                if (sy > 0) {
                    view?.evaluateJavascript("setTimeout(function(){window.scrollTo(0,$sy);},350)", null)
                }
                // 站点自定义 JS + 阅读模式
                val host = SiteScriptStore.hostOf(url ?: "")
                val siteJs = if (host.isNotBlank()) SiteScriptStore.get(ctx, host) else ""
                if (siteJs.isNotBlank()) view?.evaluateJavascript("(function(){try{$siteJs}catch(e){console.warn('LM site script',e)}})();", null)
                if (readerMode) view?.evaluateJavascript(WebScripts.readerMode, null)
                view?.evaluateJavascript(com.downloadsource.web.WebScripts.ensureViewport, null)
                // 站点级缩放记忆：回到同一站点自动套用上次的缩放
                if (url != null && url.startsWith("http")) {
                    val saved = BrowserPrefs.getHostZoom(ctx, SiteScriptStore.hostOf(url))
                    if (saved != null && saved != zoom) {
                        zoom = saved
                        view?.settings?.textZoom = saved.coerceIn(85, 130)
                    }
                }
                if (url != null && url.startsWith("http") && !autoFitDone.contains(url)) {
                    view?.evaluateJavascript("(function(){try{return (document.documentElement.scrollWidth||0)+':'+(window.innerWidth||0)}catch(e){return ''}})()") { r ->
                        val raw = r?.trim()?.trim('"') ?: ""
                        val cw = raw.substringBefore(':').toIntOrNull() ?: 0
                        val dw = raw.substringAfter(':').toIntOrNull() ?: 0
                        if (cw > 0 && dw > 0 && cw > dw * 115 / 100) {
                            autoFitDone.add(url)
                            val scale = com.downloadsource.core.ZeroCore.fitScale(cw, dw)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                runCatching {
                                    view?.setInitialScale(scale)
                                    view?.reload()
                                }
                            }, 400)
                        }
                    }
                }
                // 白屏看门狗：页面「加载完成」但正文为空（站点脚本报错/被规则挡住/渲染进程异常）
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    view?.evaluateJavascript(
                        "(function(){try{var b=document.body;if(!b)return -2;var t=(b.innerText||'').trim().length;" +
                        "var e=b.querySelectorAll('img,canvas,video').length;return (t===0&&e===0)?0:1;}catch(x){return -1;}})()"
                    ) { r ->
                        val code = r?.trim()?.trim('"')?.toIntOrNull() ?: -1
                        if (code == 0) {
                            val samePage = lastBlankWatchUrl == url
                            lastBlankWatchUrl = url
                            if (!samePage && autoReloadCount < 1) {
                                autoReloadCount += 1
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    runCatching { view?.reload() }
                                }, 600)
                            } else {
                                pageError = "页面内容为空：可能被广告规则、站点脚本或渲染进程拦截，可重试或开启兼容渲染"
                            }
                        } else {
                            autoReloadCount = 0
                        }
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // 返回 true 必须自己销毁并重建，否则视图会一直空白/黑屏
                runCatching { view?.destroy() }
                rebuildTick += 1
                android.widget.Toast.makeText(ctx, "渲染进程被系统回收，已重建页面", android.widget.Toast.LENGTH_SHORT).show()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (blockBaidu && SiteScriptStore.hostOf(target).endsWith("baidu.com") && !lastHost.endsWith("baidu.com")) {
                    android.widget.Toast.makeText(ctx, "已拦截跳转到百度", android.widget.Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                // 证书链异常的站点直接放行，避免停在空白页；安全性由用户自担
                handler?.proceed()
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse?.statusCode ?: 0
                if (request?.isForMainFrame == true && code >= 400) {
                    android.widget.Toast.makeText(ctx, "页面返回 HTTP $code", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageLoading = false
                    pageError = error?.description?.toString() ?: "未知错误"
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
                // 广告拦截：只掐广告/追踪域名，返回空响应，避免整站白屏
                if (adBlockOn && url.startsWith("http") && !request.isForMainFrame && PyRuleEngine.shouldBlock(url)) {
                    com.downloadsource.storage.AdBlockStore.addBlocked(ctx)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { adBlockedCount += 1 }
                    return android.webkit.WebResourceResponse(
                        "text/plain", "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                val ext = url.substringAfterLast('.', "").lowercase().substringBefore('?')
                if (ext in setOf("mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "flv", "m4v", "wmv", "rmvb", "3gp", "m3u8", "mpd", "m4s", "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "ape", "mka", "apk", "apks", "xapk", "apkm", "ipa", "exe", "msi", "dmg", "deb", "rpm", "zip", "rar", "7z", "tar", "gz", "xz", "zst", "iso", "img", "pdf", "epub", "mobi", "azw3", "docx", "xlsx", "pptx", "torrent") && !url.contains("favicon")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (sniffedUrls.none { it.url == url } && sniffedUrls.size < 100) {
                            sniffedUrls.add(WebInterceptor.SniffedMedia(url, null, false))
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                pageLoading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title ?: ""
                currentTab()?.let { it.title = title ?: "新标签页" }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tmp = WebView(ctx)
                tmp.settings.userAgentString = if (BrowserPrefs.isDesktop(ctx)) DESKTOP_UA else UserAgents.resolve(BrowserPrefs.getUaKey(ctx))
                val opened = java.util.concurrent.atomic.AtomicBoolean(false)
                fun adopt(target: String) {
                    if (target.isBlank() || !opened.compareAndSet(false, true)) return
                    val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", target)
                    tabs.add(tab)
                    activeId = tab.id
                    urlInput = target
                    blankPage = false
                    webView.loadUrl(target)
                    runCatching { tmp.stopLoading(); tmp.destroy() }
                }
                tmp.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean {
                        adopt(req.url.toString()); return true
                    }
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        // 有些站点 target=_blank 后直接渲染页面，不走 shouldOverride
                        if (url != null) adopt(url)
                    }
                }
                transport.webView = tmp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    filePicker.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                (view.parent as? ViewGroup)?.removeView(view)
                customView = view
                customCallback = callback
            }

            override fun onHideCustomView() {
                (customView?.parent as? ViewGroup)?.removeView(customView)
                customView = null
                customCallback = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(view?.title ?: "提示")
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(view?.title ?: "确认")
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                val input = android.widget.EditText(ctx).apply { setText(defaultValue ?: "") }
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(view?.title ?: "输入")
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                android.widget.Toast.makeText(ctx, "该链接为页面内动态流，请用嗅探列表抓取", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                pendingDownloadUrl = url
                showDownloadDialog = true
            }
        }

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val extra = hit.extra
            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                        hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && !extra.isNullOrBlank()) {
                linkMenuUrl = extra
                true
            } else false
        }
        onDispose { }
    }

    DisposableEffect(rebuildTick) {
        onDispose {
            runCatching { webView.destroy() }
        }
    }

    LaunchedEffect(Unit) {
        if (incognito) return@LaunchedEffect
        snapshotFlow { tabs.map { Quad(it.id, it.title, it.url, it.scrollY) } to activeId }
            .collect { (list, act) ->
                com.downloadsource.storage.TabStore.save(
                    ctx,
                    list.map { com.downloadsource.storage.TabStore.SavedTab(it.a, it.b, it.c, it.d) },
                    act
                )
            }
    }

    LaunchedEffect(Unit) {
        webInterceptor.sniffedData.collect { data ->
            if (sniffedUrls.none { it.url == data.url } && sniffedUrls.size < 100) sniffedUrls.add(data)
        }
    }

    LaunchedEffect(externalUrlToLoad) {
        if (externalUrlToLoad != null) {
            urlInput = externalUrlToLoad
            webView.loadUrl(externalUrlToLoad)
            onUrlLoaded()
        }
    }

    /** 切走前存快照：滚动位置 + 当前画面缩略图 */
    /** 切换 UA：统一处理 viewport / 缩放 / 渲染层，避免桌面模式划不动、换 UA 后卡顿 */
    /** 嗅探/直链下载必须带上浏览器当前会话头，否则大量站点只给片段或直接 403 */
    fun browserHeaders(target: String): Map<String, String> = buildMap {
        runCatching {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(target)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        put("Referer", runCatching { webView.url }.getOrNull() ?: target)
        put("User-Agent", runCatching { webView.settings.userAgentString }.getOrNull() ?: UserAgents.DEFAULT)
    }

    fun applyUserAgent(ua: String, desktop: Boolean) {
        val changed = runCatching { webView.settings.userAgentString != ua }.getOrDefault(true)
        runCatching {
            val st = webView.settings
            st.userAgentString = ua
            // 两种模式都要开 wideViewport + overview：桌面版页面 980px 才会被整体缩放进屏幕，
            // 关掉就会横向裁切（表现为「文字只剩左半截」）
            st.useWideViewPort = true
            st.loadWithOverviewMode = true
            st.setSupportZoom(true)
            st.builtInZoomControls = true
            st.displayZoomControls = false
            st.textZoom = if (desktop) 100 else BrowserPrefs.getZoom(ctx).coerceIn(85, 130)
            st.blockNetworkImage = false
            webView.setInitialScale(0)
            // 不再手动切 LAYER_TYPE_HARDWARE，软硬切换本身就会引起首屏抖动
            webView.isNestedScrollingEnabled = true
            webView.requestDisallowInterceptTouchEvent(false)
            webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER)
        }
        if (changed) webView.reload()
    }

    fun snapshotCurrent() {
        val tab = currentTab() ?: return
        runCatching {
            webView.evaluateJavascript("window.scrollY") { v ->
                tab.scrollY = v?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
            }
        }
        runCatching {
            val w = webView.width.coerceAtLeast(1); val h = webView.height.coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
            webView.draw(android.graphics.Canvas(bmp))
            com.downloadsource.storage.TabThumbStore.save(ctx, tab.id, bmp)
        }
    }

    /** 立即落盘，避免标题/网址变化时 snapshotFlow 抓不到 */
    fun persistTabs() {
        if (incognito) return
        runCatching {
            com.downloadsource.storage.TabStore.save(
                ctx,
                tabs.map { com.downloadsource.storage.TabStore.SavedTab(it.id, it.title, it.url, it.scrollY) },
                activeId
            )
        }
    }

    fun switchTo(tab: BrowserTab) {
        val real = tab.url.isNotBlank() && !tab.url.startsWith("about:")
        if (tab.id != activeId) {
            snapshotCurrent()
            activeId = tab.id
        }
        if (real) {
            blankPage = false
            urlInput = tab.url
            // 实际加载统一交给 LaunchedEffect(activeId)，避免同一页被 load 两次导致白屏
        } else {
            blankPage = true
        }
    }

    LaunchedEffect(activeId) {
        val tab = currentTab() ?: return@LaunchedEffect
        val target = tab.url.ifBlank { "about:blank" }
        urlInput = if (target == "about:blank") "" else target
        blankPage = target == "about:blank"
        val current = runCatching { webView.url }.getOrNull()
        if (target == "about:blank") {
            if (!current.isNullOrBlank() && !current.startsWith("about:")) {
                runCatching { webView.stopLoading() }
                webView.loadUrl("about:blank")
            }
        } else if (current != target) {
            webView.loadUrl(target)
        }
    }

    LaunchedEffect(rebuildTick) {
        if (rebuildTick > 0) {
            val url = currentTab()?.url ?: engine.homeUrl
            urlInput = url
            webView.loadUrl(url)
        }
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
    }

    if (customView != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { customView!! }, modifier = Modifier.fillMaxSize())
        }
        BackHandler {
            customCallback?.onCustomViewHidden()
            (customView?.parent as? ViewGroup)?.removeView(customView)
            customView = null
            customCallback = null
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                    .then(if (fullscreen) Modifier.height(0.dp).clipToBounds() else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiStyle == 0) Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { showTabs = true }
                        .background(palette.surface).padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tabs.size.toString(), color = palette.onSurface, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.Layers, null, tint = palette.onSurfaceDim, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.weight(1f).height(if (browserUi == 1) 50.dp else if (browserUi == 2) 38.dp else 44.dp).clip(RoundedCornerShape(if (browserUi == 1) 25.dp else if (browserUi == 2) 19.dp else 22.dp))
                        .background(palette.surface)
                        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), palette.onSurface.copy(alpha = 0.12f))), RoundedCornerShape(if (browserUi == 1) 25.dp else if (browserUi == 2) 19.dp else 22.dp))
                ) {
                    Row(Modifier.fillMaxWidth().height(if (browserUi == 1) 50.dp else if (browserUi == 2) 38.dp else 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize, lineHeight = MaterialTheme.typography.bodyMedium.fontSize),
                            cursorBrush = SolidColor(palette.primary.first()),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { doGo() }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (urlInput.isEmpty()) Text("搜索或输入网址", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f).onFocusChanged { addressFocused = it.isFocused }
                        )
                        if (urlInput.isNotEmpty()) {
                            Icon(
                                Icons.Default.Close, "清空",
                                tint = palette.onSurfaceDim,
                                modifier = Modifier.size(18.dp).clickable { urlInput = "" }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(palette.primary.first().copy(alpha = 0.12f)).clickable { doGo() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前往", tint = palette.primary.first(), modifier = Modifier.size(16.dp)) }
                    }
                }
                if (browserUi != 2) {
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        val url = webView.url ?: return@IconButton
                        if (BrowserHistoryStore.isBookmarked(ctx, url)) BrowserHistoryStore.removeBookmark(ctx, url)
                        else BrowserHistoryStore.addBookmark(ctx, url, pageTitle)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    val marked = BrowserHistoryStore.isBookmarked(ctx, webView.url ?: "")
                    Icon(if (marked) Icons.Default.Star else Icons.Default.StarBorder, "收藏", tint = if (marked) palette.primary.first() else palette.onSurfaceDim, modifier = Modifier.size(20.dp))
                }
                }
                if (uiStyle == 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { showEnginePicker = true }
                        .background(palette.surface).padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(engine.displayName, color = palette.primary.first(), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                }
                }
            }

            if (pageLoading && progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = palette.primary.first())
            }

            if (showFind) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(palette.surface).padding(10.dp)
                    )
                    TextButton(onClick = { webView.findAllAsync(findQuery) }) { Text("查找") }
                    TextButton(onClick = { webView.findNext(true) }) { Text("下一个") }
                    TextButton(onClick = { webView.clearMatches(); showFind = false }) { Text("关闭") }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                val tabBlank = currentTab()?.url?.let { it.isBlank() || it.startsWith("about:") } ?: true
                // 只要当前标签没有真实网址，就一定显示起始页，绝不留纯白
                if (tabBlank) {
                    BrowserStartPage(
                        palette = palette,
                        incognito = incognito,
                        query = urlInput,
                        onQueryChange = { urlInput = it },
                        onSearch = {
                            val target = SearchEngine.normalize(ctx, urlInput)
                            if (target.isNotBlank()) {
                                blankPage = false
                                urlInput = target
                                syncTab(target)
                                webView.loadUrl(target)
                            }
                        },
                        onOpen = { link ->
                            blankPage = false
                            val cur = currentTab()
                            if (cur != null && (cur.url.isBlank() || cur.url.startsWith("about:"))) {
                                cur.url = link
                                urlInput = link
                                webView.loadUrl(link)
                                return@BrowserStartPage
                            }
                            val t = BrowserTab(idGen.getAndIncrement(), "新标签页", link)
                            tabs.add(t); activeId = t.id
                            webView.loadUrl(link)
                        },
                        onEngines = { showEnginePicker = true },
                        onDismiss = { blankPage = false; webView.loadUrl(engine.homeUrl) }
                    )
                }
                pageError?.let { err ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.surface)
                            .padding(20.dp)
                    ) {
                        Text("页面打不开", fontWeight = FontWeight.Bold, color = palette.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(err, color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(onClick = { pageError = null; webView.reload() }) { Text("重试") }
                            TextButton(onClick = {
                                pageError = null
                                BrowserPrefs.setDesktop(ctx, false)
                                desktopMode = false
                                applyUserAgent(UserAgents.resolve(BrowserPrefs.getUaKey(ctx)), false)
                            }) { Text("换移动 UA") }
                            TextButton(onClick = {
                                val host = SiteScriptStore.hostOf(webView.url ?: urlInput)
                                val on = RenderCompatStore.toggle(ctx, host)
                                pageError = null
                                rebuildTick += 1
                                android.widget.Toast.makeText(ctx, if (on) "已开启兼容渲染" else "已关闭兼容渲染", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text(if (RenderCompatStore.isSoftware(ctx, SiteScriptStore.hostOf(webView.url ?: urlInput))) "关闭兼容渲染" else "兼容渲染") }
                        }
                    }
                }
            }

            Row(
                Modifier.then(
                    when (uiStyle) {
                        2 -> Modifier.fillMaxWidth(0.82f).align(Alignment.CenterHorizontally).padding(vertical = 6.dp)
                        else -> Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    }
                )
                    .clip(RoundedCornerShape(28.dp)).background(palette.surface)
                    .padding(vertical = 6.dp)
                    .then(if (fullscreen) Modifier.height(0.dp).clipToBounds() else Modifier),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (webView.canGoBack()) webView.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", tint = palette.onSurface)
                }
                IconButton(onClick = { if (webView.canGoForward()) webView.goForward() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进", tint = palette.onSurface)
                }
                IconButton(onClick = { webView.loadUrl(engine.homeUrl) }) {
                    Icon(Icons.Default.Home, "主页", tint = palette.onSurface)
                }
                IconButton(onClick = {
                    if (pageLoading) { webView.stopLoading(); pageLoading = false; progress = 100 } else webView.reload()
                }) {
                    Icon(if (pageLoading) Icons.Default.Close else Icons.Default.Refresh, if (pageLoading) "停止" else "刷新", tint = palette.onSurface)
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多", tint = palette.onSurface)
                }
            }
        }

        BadgedBox(
            badge = { if (sniffedUrls.isNotEmpty()) Badge { Text(sniffedUrls.size.toString()) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 82.dp)
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (sniffedUrls.isEmpty()) palette.onSurfaceDim.copy(alpha = 0.4f) else palette.primary.first())
                    .combinedClickable(
                        onClick = {
                            if (sniffedUrls.isEmpty()) android.widget.Toast.makeText(ctx, "暂未抓取到资源", android.widget.Toast.LENGTH_SHORT).show()
                            else showSniffList = true
                        },
                        onLongClick = { showTabs = true }
                    )
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.Layers, "资源嗅探 / 长按看标签", tint = Color.White)
            }
        }
    }

    if (showMenu) {
        Dialog(
            onDismissRequest = { showMenu = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(palette.background.first()).padding(top = 40.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("菜单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = palette.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showMenu = false }) { Text("关闭") }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                LazyColumn(Modifier.weight(1f)) {
                    item { MenuItem("新标签页", Icons.Default.Add, palette) {
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", "about:blank")
                        tabs.add(tab); activeId = tab.id; showMenu = false
                    } }
                    item { MenuItem("标签页总览（${tabs.size}）", Icons.Default.Layers, palette) { showMenu = false; showTabs = true } }
                    item { MenuItem("书签", Icons.Default.Star, palette) { showMenu = false; showBookmarks = true } }
                    item { MenuItem("历史记录", Icons.Default.Search, palette) { showMenu = false; showHistory = true } }
                    item { MenuItem("页内查找", Icons.Default.Search, palette) { showMenu = false; showFind = true } }
                    item { MenuItem("浏览器标识 (UA)", Icons.Default.Layers, palette) { showMenu = false; showUa = true } }
                    item { MenuItem("此站自定义 JS", Icons.Default.Add, palette) {
                        showMenu = false
                        val host = SiteScriptStore.hostOf(webView.url ?: "")
                        siteScriptText = if (host.isNotBlank()) SiteScriptStore.get(ctx, host) else ""
                        showSiteScript = true
                    } }
                    item { MenuItem(if (readerMode) "退出阅读模式" else "阅读模式", Icons.Default.Search, palette) {
                        showMenu = false
                        readerMode = !readerMode
                        webView.evaluateJavascript(WebScripts.readerMode, null)
                    } }
                    item { MenuItem("兼容渲染（治黑屏）", Icons.Default.Layers, palette) {
                        showMenu = false
                        val host = SiteScriptStore.hostOf(webView.url ?: urlInput)
                        if (host.isBlank()) {
                            android.widget.Toast.makeText(ctx, "当前没有可设置的站点", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val on = RenderCompatStore.toggle(ctx, host)
                            rebuildTick += 1
                            android.widget.Toast.makeText(ctx, if (on) "已对本站开启兼容渲染" else "已关闭本站兼容渲染", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } }
                    item { MenuItem("另存网页 HTML", Icons.Default.Add, palette) { showMenu = false; savePageHtml(ctx, webView) } }
                    item { MenuItem("长截图保存", Icons.Default.Layers, palette) { showMenu = false; saveLongScreenshot(ctx, webView, palette) } }
                    item { MenuItem("导出 PDF", Icons.Default.Refresh, palette) { showMenu = false; exportPdf(ctx, webView) } }
                    item { MenuItem("分享当前页面", Icons.AutoMirrored.Filled.Send, palette) {
                        showMenu = false
                        val target = webView.url ?: urlInput
                        if (target.isNotBlank()) {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, pageTitle.ifBlank { target })
                                    putExtra(android.content.Intent.EXTRA_TEXT, pageTitle.ifBlank { "" } + "\n" + target)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }.onFailure { android.widget.Toast.makeText(ctx, "没有可分享的应用", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    } }
                    item { MenuItem(if (ttsSpeaking) "停止朗读" else "朗读正文", Icons.Default.PlayArrow, palette) {
                        showMenu = false
                        if (ttsSpeaking) {
                            runCatching { tts?.stop() }
                            ttsSpeaking = false
                        } else {
                            webView.evaluateJavascript("(function(){try{return (document.body?document.body.innerText:'').slice(0,6000)}catch(e){return ''}})()") { r ->
                                val text = runCatching { org.json.JSONTokener(r ?: "").nextValue() as String }.getOrDefault("")
                                if (text.isBlank()) {
                                    android.widget.Toast.makeText(ctx, "页面没有可朗读的文字", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching {
                                        tts?.language = java.util.Locale.getDefault()
                                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "lm_read")
                                        ttsSpeaking = true
                                    }.onFailure { android.widget.Toast.makeText(ctx, "朗读不可用", android.widget.Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    } }
                    item { MenuItem("清理浏览器缓存", Icons.Default.Delete, palette) {
                        showMenu = false
                        runCatching {
                            webView.clearCache(true)
                            webView.clearFormData()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            android.widget.Toast.makeText(ctx, "缓存已清理（登录状态保留）", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } }
                    item { MenuItem("记住本站文字缩放", Icons.Default.Refresh, palette) {
                        showMenu = false
                        val host = SiteScriptStore.hostOf(webView.url ?: urlInput)
                        if (host.isBlank()) {
                            android.widget.Toast.makeText(ctx, "当前没有可记录的站点", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            BrowserPrefs.setHostZoom(ctx, host, zoom)
                            android.widget.Toast.makeText(ctx, "已记住 $host 的缩放为 $zoom%", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } }
                    item { MenuItem("数据管理", Icons.Default.Delete, palette) { showMenu = false; showDataPanel = true } }
                    item { HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f)) }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, null, tint = palette.onSurfaceDim, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("网页广告拦截", color = palette.onSurface)
                                Text("已拦 ${adBlockedCount} 条 · 关闭后完全放行", color = palette.onSurfaceDim, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = adBlockOn,
                                onCheckedChange = {
                                    adBlockOn = it
                                    com.downloadsource.storage.AdBlockStore.setEnabled(ctx, it)
                                    if (!it) com.downloadsource.storage.AdBlockStore.resetCount(ctx)
                                    adBlockedCount = com.downloadsource.storage.AdBlockStore.blockedCount(ctx)
                                    android.widget.Toast.makeText(ctx, if (it) "广告拦截已开启" else "广告拦截已关闭，广告将正常显示", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                    item { HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f)) }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("无痕浏览", color = palette.onSurface)
                                Text("不记历史、不保存标签页", color = palette.onSurfaceDim, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(checked = incognito, onCheckedChange = {
                                incognito = it
                                BrowserPrefs.setIncognito(ctx, it)
                                android.widget.Toast.makeText(ctx, if (it) "已开启无痕浏览" else "已关闭无痕浏览", android.widget.Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("拦截跳转百度", color = palette.onSurface, modifier = Modifier.weight(1f))
                            Switch(checked = blockBaidu, onCheckedChange = { blockBaidu = it; BrowserPrefs.setBlockBaidu(ctx, it) })
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("桌面版网站", color = palette.onSurface, modifier = Modifier.weight(1f))
                            Switch(checked = desktopMode, onCheckedChange = {
                                desktopMode = it
                                BrowserPrefs.setDesktop(ctx, it)
                                applyUserAgent(if (it) DESKTOP_UA else UserAgents.resolve(BrowserPrefs.getUaKey(ctx)), it)
                            })
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("文字缩放", color = palette.onSurface, modifier = Modifier.weight(1f))
                            listOf(90, 100, 125, 150).forEach { z ->
                                Text(
                                    "$z%",
                                    color = if (zoom == z) palette.primary.first() else palette.onSurfaceDim,
                                    fontWeight = if (zoom == z) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clickable {
                                        zoom = z; BrowserPrefs.setZoom(ctx, z); webView.settings.textZoom = z.coerceIn(85, 130)
                                    }.padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                    item { HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f)) }
                    item { MenuItem(if (fullscreen) "退出全屏" else "全屏浏览", Icons.Default.Layers, palette) { showMenu = false; fullscreen = !fullscreen } }
                    item { MenuItem("复制当前网址", Icons.Default.Add, palette) {
                        showMenu = false
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", webView.url ?: ""))
                        android.widget.Toast.makeText(ctx, "网址已复制", android.widget.Toast.LENGTH_SHORT).show()
                    } }
                    item { MenuItem("关闭全部标签", Icons.Default.Delete, palette) {
                        showMenu = false
                        tabs.forEach { com.downloadsource.storage.TabThumbStore.clear(ctx, it.id) }
                        tabs.clear()
                        val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                        tabs.add(fresh)
                        activeId = fresh.id
                    } }
                    item { MenuItem("分享当前页", Icons.Default.Search, palette) { showMenu = false; sharePage(ctx, webView.url ?: "") } }
                    item { MenuItem("用系统浏览器打开", Icons.Default.Refresh, palette) { showMenu = false; openExternal(ctx, webView.url ?: "") } }
                    item { MenuItem("清除缓存与 Cookie", Icons.Default.Delete, palette) { showMenu = false; clearBrowserData(ctx, webView) } }
                    item { MenuItem("关闭当前标签", Icons.Default.Close, palette) {
                        showMenu = false
                        val tab = currentTab()
                        if (tab != null) {
                            tabs.remove(tab)
                            if (tabs.isEmpty()) {
                                val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                                tabs.add(fresh); activeId = fresh.id
                            } else {
                                activeId = tabs.first().id
                            }
                        }
                    } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showTabs) {
        Dialog(
            onDismissRequest = { showTabs = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxSize().background(palette.background.first())
                    .padding(horizontal = 14.dp).padding(top = 44.dp, bottom = 14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("标签页总览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = palette.onSurface)
                        Text("${tabs.size} 个标签页 · 长按卡片可操作", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", "about:blank")
                        tabs.add(tab); activeId = tab.id; showTabs = false
                    }) { Text("新建") }
                    TextButton(onClick = { showTabs = false }) { Text("完成") }
                }
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    gridItems(tabs.toList(), key = { it.id }) { tab ->
                        val active = tab.id == activeId
                        Column(
                            Modifier.fillMaxWidth().height(160.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (active) palette.primary.first().copy(alpha = 0.14f) else palette.surface)
                                .border(
                                    width = if (active) 2.dp else 1.dp,
                                    color = if (active) palette.primary.first() else palette.onSurface.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        switchTo(tab)
                                        showTabs = false
                                        val target = tab.url.ifBlank { "about:blank" }
                                        urlInput = target
                                        blankPage = target.startsWith("about:")
                                        webView.loadUrl(target)
                                    },
                                    onLongClick = { tabMenuId = tab.id }
                                )
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (active) "当前" else "${tabs.indexOf(tab) + 1}",
                                    color = if (active) palette.primary.first() else palette.onSurfaceDim,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.Close, "关闭标签页", tint = palette.onSurfaceDim,
                                    modifier = Modifier.size(18.dp).clickable {
                                        com.downloadsource.storage.TabThumbStore.clear(ctx, tab.id)
                                        tabs.remove(tab)
                                        if (tabs.isEmpty()) {
                                            val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                                            tabs.add(fresh); activeId = fresh.id
                                        } else if (tab.id == activeId) {
                                            activeId = tabs.first().id
                                        }
                                    }.padding(2.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            val thumb = remember(tab.id) { com.downloadsource.storage.TabThumbStore.get(ctx, tab.id) }
                            if (thumb != null) {
                                val bmp = remember(tab.id) {
                                    runCatching { android.graphics.BitmapFactory.decodeFile(thumb.absolutePath) }.getOrNull()
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(), contentDescription = tab.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().height(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            Text(
                                tab.title.ifBlank { "新标签页" },
                                color = palette.onSurface, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                SiteScriptStore.hostOf(tab.url).ifBlank { tab.url },
                                color = palette.onSurfaceDim, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                                .background(palette.surface.copy(alpha = 0.6f))
                                .clickable {
                                    val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                                    tabs.add(tab); activeId = tab.id; showTabs = false
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Add, "新建标签页", tint = palette.onSurfaceDim)
                            Spacer(Modifier.height(6.dp))
                            Text("新建标签页", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    linkMenuUrl?.let { link ->
        Dialog(onDismissRequest = { linkMenuUrl = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.surface)) {
                Text(
                    SiteScriptStore.hostOf(link).ifBlank { link },
                    color = palette.onSurface, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp)
                )
                Text(
                    link, color = palette.onSurfaceDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)
                )
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.08f))
                listOf(
                    "在新标签页打开" to {
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", link)
                        tabs.add(tab); activeId = tab.id
                        linkMenuUrl = null
                    },
                    "后台打开（不切换）" to {
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", link)
                        tabs.add(tab)
                        android.widget.Toast.makeText(ctx, "已在后台打开，长按悬浮球查看", android.widget.Toast.LENGTH_SHORT).show()
                        linkMenuUrl = null
                    },
                    "复制链接" to {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", link))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        linkMenuUrl = null
                    },
                    "下载这个文件" to {
                        pendingDownloadUrl = link; showDownloadDialog = true; linkMenuUrl = null
                    }
                ).forEach { (label, action) ->
                    Text(
                        label, color = palette.onSurface, modifier = Modifier.fillMaxWidth()
                            .clickable { action() }.padding(horizontal = 18.dp, vertical = 13.dp)
                    )
                }
            }
        }
    }

    if (tabMenuId >= 0) {
        val tab = tabs.firstOrNull { it.id == tabMenuId }
        Dialog(onDismissRequest = { tabMenuId = -1 }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(vertical = 8.dp)) {
                Text("标签页操作", fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.padding(16.dp, 12.dp))
                Text("复制网址", color = palette.onSurface, modifier = Modifier.fillMaxWidth().clickable {
                    tab?.let {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", it.url))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    tabMenuId = -1
                }.padding(16.dp, 12.dp))
                Text("关闭此标签页", color = Color(0xFFFF5A5A), modifier = Modifier.fillMaxWidth().clickable {
                    tab?.let { t ->
                        tabs.remove(t)
                        if (tabs.isEmpty()) {
                            val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                            tabs.add(fresh); activeId = fresh.id
                        } else if (t.id == activeId) activeId = tabs.first().id
                    }
                    tabMenuId = -1
                }.padding(16.dp, 12.dp))
            }
        }
    }

    if (showBookmarks) {
        val list = BrowserHistoryStore.getBookmarks(ctx)
        Dialog(onDismissRequest = { showBookmarks = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("书签 ${list.size}", fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val url = webView.url ?: return@TextButton
                        BrowserHistoryStore.addBookmark(ctx, url, pageTitle); showBookmarks = false
                    }) { Text("收藏当前页") }
                }
                if (list.isEmpty()) Text("还没有书签", color = palette.onSurfaceDim)
                LazyColumn {
                    items(list) { item ->
                        Row(Modifier.fillMaxWidth().clickable { urlInput = item.url; webView.loadUrl(item.url); showBookmarks = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, color = palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { BrowserHistoryStore.removeBookmark(ctx, item.url) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, "删除", tint = palette.onSurfaceDim)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistory) {
        val list = BrowserHistoryStore.getHistory(ctx)
        Dialog(onDismissRequest = { showHistory = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("历史记录 ${list.size}", fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = { BrowserHistoryStore.clearHistory(ctx); showHistory = false }) { Text("清空") }
                }
                if (list.isEmpty()) Text("暂无记录", color = palette.onSurfaceDim)
                LazyColumn {
                    items(list) { url ->
                        Text(url, color = palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { urlInput = url; webView.loadUrl(url); showHistory = false }.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }

    if (showSniffList) {
        LaunchedEffect(showSniffList) {
            sniffedUrls.filter { sniffKind(it.url) == "视频" && !it.isBlob }.take(8).forEach { media ->
                launch { probeMap[media.url] = com.downloadsource.core.MediaProbe.probe(media.url) }
            }
        }
        Dialog(onDismissRequest = { showSniffList = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已抓取资源 (${sniffedUrls.size})", fontWeight = FontWeight.Bold, color = palette.onSurface)
                    Row {
                        TextButton(onClick = {
                            var added = 0
                            sniffedUrls.take(20).forEach { media ->
                                if (!media.isBlob) {
                                    DownloadManager.add(ctx, media.url, null, 16, 0, browserHeaders(media.url), DirStore.resolveDownloadDir(ctx))
                                    added++
                                }
                            }
                            android.widget.Toast.makeText(ctx, "已入队 $added 个任务", android.widget.Toast.LENGTH_SHORT).show()
                            showSniffList = false
                        }) { Text("全部下载") }
                        TextButton(onClick = {
                            val videos = sniffedUrls.filter { sniffKind(it.url) == "视频" && !it.isBlob }.take(10)
                            videos.forEach { DownloadManager.add(ctx, it.url, null, 16, 0, browserHeaders(it.url), DirStore.resolveDownloadDir(ctx)) }
                            android.widget.Toast.makeText(ctx, "已入队 ${videos.size} 个视频", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("仅视频") }
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("links", sniffedUrls.joinToString("\n") { it.url }))
                            android.widget.Toast.makeText(ctx, "已复制 ${sniffedUrls.size} 条链接", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("复制全部") }
                        TextButton(onClick = { sniffedUrls.clear(); showSniffList = false }) { Text("清空") }
                    }
                }
                val grouped = sniffedUrls.groupBy { sniffKind(it.url) }
                val order = listOf("视频", "音频", "安装包", "压缩包", "文档", "图片", "其它")
                LazyColumn {
                    order.forEach { kind ->
                        val list = grouped[kind] ?: return@forEach
                        val sorted = if (kind == "视频") list.sortedByDescending { qualityScore(it.url) } else list
                        item(key = "h_$kind") {
                            Text("$kind · ${list.size}", color = palette.onSurfaceDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        items(sorted) { media ->
                            val info = probeMap[media.url] ?: com.downloadsource.core.MediaProbe.cached(media.url)
                            val kind = sniffKind(media.url)
                            val sizeText = info?.let { com.downloadsource.core.MediaProbe.formatSize(it.size) }?.takeIf { it.isNotBlank() }
                            val durText = info?.let { com.downloadsource.core.MediaProbe.formatDuration(it.durationMs) }?.takeIf { it.isNotBlank() }
                            val q = qualityScore(media.url)
                            val qLabel = when {
                                q >= 2000 -> "4K"
                                q >= 1200 -> "2K"
                                q >= 900 -> "1080P"
                                q >= 650 -> "720P"
                                q >= 500 -> "540P"
                                q > 0 -> "${q}P"
                                else -> null
                            }
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                                    .background(palette.surface.copy(alpha = 0.92f)).padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.width(104.dp).height(64.dp).clip(RoundedCornerShape(10.dp))
                                            .background(Brush.linearGradient(listOf(palette.primary.first().copy(alpha = 0.85f), palette.primary.last().copy(alpha = 0.7f))))
                                    ) {
                                        Icon(
                                            if (kind == "视频") Icons.Default.PlayArrow else Icons.Default.Layers,
                                            null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(26.dp)
                                        )
                                        if (durText != null) Text(
                                            durText, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                                .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (qLabel != null) {
                                                Text(qLabel, color = palette.primary.first(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                if (media.isBlob) "加密/动态流" else media.url.substringAfterLast('/').ifBlank { kind },
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            listOfNotNull(if (media.isBlob) "Blob" else kind, sizeText, durText?.let { "时长 $it" }).joinToString(" · ").ifBlank { "读取信息中…" },
                                            color = palette.onSurfaceDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val downloadMod = Modifier.size(36.dp).clip(CircleShape).background(palette.primary.first())
                                        .clickable {
                                            pendingCustomName = ""
                                            pendingDownloadUrl = media.url
                                            showDownloadDialog = true
                                            showSniffList = false
                                        }
                                    Box(
                                        downloadMod, contentAlignment = Alignment.Center
                                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "下载", tint = Color.White, modifier = Modifier.size(18.dp)) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (kind == "视频" && !media.isBlob) TextButton(onClick = { previewUrl = media.url }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("预览", style = MaterialTheme.typography.labelMedium) }
                                    TextButton(onClick = {
                                        renamingUrl = media.url
                                        renameDraft = media.url.substringAfterLast('/').substringBefore('?').ifBlank { "video_${System.currentTimeMillis()}" }
                                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("重命名", style = MaterialTheme.typography.labelMedium) }
                                    TextButton(onClick = {
                                        val cm2 = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm2.setPrimaryClip(android.content.ClipData.newPlainText("url", media.url))
                                        android.widget.Toast.makeText(ctx, "已复制链接", android.widget.Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("复制", style = MaterialTheme.typography.labelMedium) }
                                }
                                if (renamingUrl == media.url) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                        BasicTextField(
                                            value = renameDraft,
                                            onValueChange = { renameDraft = it },
                                            singleLine = true,
                                            textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                            cursorBrush = SolidColor(palette.primary.first()),
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(palette.background.first().copy(alpha = 0.5f)).padding(10.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        TextButton(onClick = { renamingUrl = null }) { Text("取消", style = MaterialTheme.typography.labelMedium) }
                                        TextButton(onClick = {
                                            val nm = renameDraft.trim()
                                            renamingUrl = null
                                            pendingCustomName = nm
                                            pendingDownloadUrl = media.url
                                            showDownloadDialog = true; showSniffList = false
                                        }) { Text("下载", style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewUrl?.let { purl ->
        Dialog(onDismissRequest = { previewUrl = null }) {
            val previewPlayer = remember(purl) {
                androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(purl))
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(purl) { onDispose { previewPlayer.release() } }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(12.dp)) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c -> androidx.media3.ui.PlayerView(c).apply { player = previewPlayer; useController = true } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { previewUrl = null }) { Text("关闭") }
                    TextButton(onClick = {
                        previewUrl = null
                        pendingDownloadUrl = purl
                        showDownloadDialog = true
                        showSniffList = false
                    }) { Text("下载") }
                }
            }
        }
    }

    if (showEnginePicker) {
        Dialog(onDismissRequest = { showEnginePicker = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Text("选择搜索引擎", fontWeight = FontWeight.Bold, color = palette.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("国内 / 国际共 ${SearchEngine.entries.size} 个，选择后地址栏搜索立即生效", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(SearchEngine.entries.size) { idx ->
                        val e = SearchEngine.entries[idx]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                engine = e
                                BrowserPrefs.setEngine(ctx, e.displayName)
                                showEnginePicker = false
                                android.widget.Toast.makeText(ctx, "搜索引擎：${e.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(e.displayName, color = if (engine == e) palette.primary.first() else palette.onSurface, modifier = Modifier.weight(1f))
                            Text(e.region, color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                            if (engine == e) Text(" · 当前", color = palette.primary.first(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showUa) {
        Dialog(onDismissRequest = { showUa = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Text("浏览器标识", fontWeight = FontWeight.Bold, color = palette.onSurface)
                Spacer(Modifier.height(8.dp))
                UserAgents.PRESETS.forEach { preset ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            uaKey = preset.key
                            BrowserPrefs.setUaKey(ctx, preset.key)
                            BrowserPrefs.setDesktop(ctx, false)
                            desktopMode = false
                            applyUserAgent(preset.ua, preset.key.contains("windows") || preset.key.contains("mac"))
                            showUa = false
                        }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(preset.label, color = if (uaKey == preset.key) palette.primary.first() else palette.onSurface, modifier = Modifier.weight(1f))
                        if (uaKey == preset.key) Text("当前", color = palette.primary.first(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showSiteScript) {
        Dialog(onDismissRequest = { showSiteScript = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Text("站点自定义 JS · ${SiteScriptStore.hostOf(webView.url ?: "")}", fontWeight = FontWeight.Bold, color = palette.onSurface)
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = siteScriptText,
                    onValueChange = { siteScriptText = it },
                    textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(palette.background.first()).padding(10.dp)) {
                            if (siteScriptText.isEmpty()) Text("// 每次页面加载后自动执行", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val host = SiteScriptStore.hostOf(webView.url ?: "")
                        if (host.isNotBlank()) { SiteScriptStore.put(ctx, host, siteScriptText); android.widget.Toast.makeText(ctx, "已保存，刷新后生效", android.widget.Toast.LENGTH_SHORT).show() }
                        showSiteScript = false
                    }) { Text("保存") }
                    TextButton(onClick = { showSiteScript = false }) { Text("取消") }
                }
            }
        }
    }

    if (showDataPanel) {
        Dialog(onDismissRequest = { showDataPanel = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Text("数据管理", fontWeight = FontWeight.Bold, color = palette.onSurface)
                MenuItem("清 Cookie", Icons.Default.Delete, palette) { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); android.widget.Toast.makeText(ctx, "Cookie 已清", android.widget.Toast.LENGTH_SHORT).show() }
                MenuItem("清缓存", Icons.Default.Delete, palette) { webView.clearCache(true); android.widget.Toast.makeText(ctx, "缓存已清", android.widget.Toast.LENGTH_SHORT).show() }
                MenuItem("清表单与历史", Icons.Default.Delete, palette) { webView.clearFormData(); webView.clearHistory(); BrowserHistoryStore.clearHistory(ctx); android.widget.Toast.makeText(ctx, "已清理", android.widget.Toast.LENGTH_SHORT).show() }
                MenuItem("清站点存储", Icons.Default.Delete, palette) { android.webkit.WebStorage.getInstance().deleteAllData(); android.widget.Toast.makeText(ctx, "站点数据已清", android.widget.Toast.LENGTH_SHORT).show() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("无痕模式（关闭 Cookie 与缓存）", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = incognito, onCheckedChange = {
                        incognito = it
                        CookieManager.getInstance().setAcceptCookie(!it)
                        webView.settings.cacheMode = if (it) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                        if (it) { CookieManager.getInstance().removeAllCookies(null); webView.clearCache(true) }
                        webView.reload()
                    })
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("网页强制深色", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = forceDarkWeb, onCheckedChange = {
                            forceDarkWeb = it
                            webView.settings.forceDark = if (it) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            webView.reload()
                        })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("无图模式（省流量）", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = !imagesOn, onCheckedChange = {
                        imagesOn = !it
                        webView.settings.loadsImagesAutomatically = imagesOn
                        if (imagesOn) webView.reload()
                    })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("仅第一方 Cookie", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = !thirdPartyCookies, onCheckedChange = {
                        thirdPartyCookies = !it
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookies)
                        android.widget.Toast.makeText(ctx, if (thirdPartyCookies) "已允许第三方 Cookie" else "已阻止第三方 Cookie", android.widget.Toast.LENGTH_SHORT).show()
                    })
                }
                TextButton(onClick = { showDataPanel = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }

    if (showDownloadDialog) {
        AddTaskDialog(initialUrl = pendingDownloadUrl, initialName = pendingCustomName, onDismiss = { showDownloadDialog = false; pendingCustomName = "" }) { url, name, threads, speed, headers ->
            DownloadManager.add(ctx, url, name, threads, speed, if (headers.isEmpty()) browserHeaders(url) else headers, DirStore.resolveDownloadDir(ctx))
            showDownloadDialog = false
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, palette: GradientPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = palette.onSurfaceDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = palette.onSurface)
    }
}

private fun sharePage(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    try {
        ctx.startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        })
    } catch (e: Exception) {}
}

private fun openExternal(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {}
}

private fun clearBrowserData(ctx: android.content.Context, webView: WebView) {
    runCatching { CookieManager.getInstance().removeAllCookies(null) }
    runCatching { CookieManager.getInstance().flush() }
    runCatching { webView.clearCache(true) }
    runCatching { webView.clearHistory() }
    runCatching { webView.clearFormData() }
    android.widget.Toast.makeText(ctx, "缓存与 Cookie 已清除", android.widget.Toast.LENGTH_SHORT).show()
}

/** 长截图：分块滚动逐屏绘制再拼成整页，最多 12000px，避免 OOM */
private fun saveLongScreenshot(ctx: android.content.Context, webView: WebView, palette: GradientPalette) {
    webView.evaluateJavascript(
        "(function(){try{var h=Math.max(document.documentElement.scrollHeight||0,document.body?document.body.scrollHeight:0);return String(h);}catch(e){return '0';}})()"
    ) { raw ->
        val contentH = raw?.trim()?.trim('"')?.toIntOrNull() ?: 0
        val w = webView.width.coerceAtLeast(1)
        val vh = webView.height.coerceAtLeast(1)
        val total = contentH.coerceIn(vh, 12000)
        val originalScroll = webView.scrollY
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        val bmp = try {
            android.graphics.Bitmap.createBitmap(w, total, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            android.widget.Toast.makeText(ctx, "页面太长，内存不足，已改截当前屏", android.widget.Toast.LENGTH_SHORT).show()
            saveViewportShot(ctx, webView)
            return@evaluateJavascript
        }
        val canvas = android.graphics.Canvas(bmp)
        var y = 0
        var lastDrawn = -1
        lateinit var step: Runnable
        step = Runnable {
            if (y >= total) {
                runCatching { webView.scrollTo(0, originalScroll) }
                finishLongShot(ctx, bmp)
                return@Runnable
            }
            if (lastDrawn != y) {
                webView.scrollTo(0, y)
                lastDrawn = y
                handler.postDelayed(step, 260)
                return@Runnable
            }
            canvas.save()
            canvas.translate(0f, y.toFloat())
            runCatching { webView.draw(canvas) }
            canvas.restore()
            y += vh
            lastDrawn = -1
            handler.postDelayed(step, 80)
        }
        handler.post(step)
    }
}

private fun saveViewportShot(ctx: android.content.Context, webView: WebView) {
    runCatching {
        val bmp = android.graphics.Bitmap.createBitmap(webView.width.coerceAtLeast(1), webView.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        webView.draw(android.graphics.Canvas(bmp))
        finishLongShot(ctx, bmp)
    }
}

private fun finishLongShot(ctx: android.content.Context, bmp: android.graphics.Bitmap) {
    try {
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "LM")
            .let { if (it.exists() || it.mkdirs()) it else java.io.File(ctx.filesDir, "shots") }
        val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it) }
        android.widget.Toast.makeText(ctx, "已保存：${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "截图失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    } finally {
        bmp.recycle()
    }
}

/** 网页导出 PDF（走系统打印框架） */
private fun exportPdf(ctx: android.content.Context, webView: WebView) {
    try {
        val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        val adapter: android.print.PrintDocumentAdapter = webView.createPrintDocumentAdapter("LM")
        pm.print("LM 网页导出", adapter, android.print.PrintAttributes.Builder().build())
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 把当前网页的 outerHTML 存到下载目录，方便离线留存 */
private fun savePageHtml(ctx: android.content.Context, webView: WebView) {
    webView.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { value ->
        if (value.isNullOrBlank() || value == "null") return@evaluateJavascript
        try {
            val html = runCatching { org.json.JSONTokener(value).nextValue() as String }.getOrDefault(value)
            val dir = java.io.File(DirStore.resolveDownloadDir(ctx), "LM Pages").apply { mkdirs() }
            val file = java.io.File(dir, "page_${System.currentTimeMillis()}.html")
            file.writeText(html)
            android.widget.Toast.makeText(ctx, "已保存：${file.name}", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "保存失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** 按扩展名给嗅探结果分组，便于批量挑同类资源 */
private fun sniffKind(url: String): String {
    val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "ts", "m3u8", "mpd", "ogv", "wmv", "rmvb", "3gp" -> "视频"
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape" -> "音频"
        "apk", "xapk", "apks", "aab", "ipa", "exe", "msi", "dmg" -> "安装包"
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst", "iso", "torrent" -> "压缩包"
        "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml" -> "文档"
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic" -> "图片"
        else -> "其它"
    }
}

/** 从 URL 里猜清晰度，高的排前面（1080/720/540/360） */
private fun qualityScore(url: String): Int {
    val u = url.lowercase()
    return when {
        u.contains("2160") || u.contains("4k") -> 5
        u.contains("1440") || u.contains("2k") -> 4
        u.contains("1080") || u.contains("fhd") -> 3
        u.contains("720") || u.contains("hd") -> 2
        u.contains("540") || u.contains("480") -> 1
        else -> 0
    }
}


/** 新标签起始页：大搜索框 + 常用站点网格，空白页不再是纯白 */
@Composable
private fun BrowserStartPage(
    palette: GradientPalette,
    incognito: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onEngines: () -> Unit,
    onDismiss: () -> Unit
) {
    val sites = listOf(
        "百度" to "https://www.baidu.com",
        "B站" to "https://m.bilibili.com",
        "知乎" to "https://www.zhihu.com",
        "微博" to "https://m.weibo.cn",
        "抖音" to "https://www.douyin.com",
        "淘宝" to "https://m.taobao.com",
        "GitHub" to "https://github.com",
        "YouTube" to "https://m.youtube.com"
    )
    Column(
        Modifier.fillMaxSize().background(palette.background.first())
            .padding(horizontal = 22.dp).padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (incognito) "无痕浏览" else "LM Browser",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold, color = palette.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (incognito) "本次浏览不留痕迹" else "输入网址，或先选一个站点",
            color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.primary.first()),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("搜索或输入网址", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.PlayArrow, "前往", tint = palette.primary.first(),
                    modifier = Modifier.size(20.dp).clickable { onSearch() }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "常用站点", color = palette.onSurfaceDim, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            gridItems(sites, key = { it.first }) { (name, url) ->
                Column(
                    Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(16.dp))
                        .background(palette.surface).clickable { onOpen(url) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(palette.primary.first().copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            name.take(1), color = palette.primary.first(),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(name, color = palette.onSurface, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEngines) { Text("切换搜索引擎") }
            TextButton(onClick = onDismiss) { Text("跳过，直接浏览") }
        }
    }
}
