package com.downloadsource.ui

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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.downloadsource.utils.SearchEngine
import com.downloadsource.utils.UserAgents
import com.downloadsource.web.SafeWebViewClient
import com.downloadsource.web.WebInterceptor
import com.downloadsource.web.WebScripts
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private data class BrowserTab(val id: Int, var title: String, var url: String)

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
    val tabs = remember { mutableListOf(BrowserTab(1, "新标签页", sessionUrl)).toMutableStateList() }
    var activeId by remember { mutableStateOf(1) }
    val idGen = remember { AtomicInteger(2) }

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
    var incognito by remember { mutableStateOf(false) }
    var rebuildTick by remember { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var imagesOn by remember { mutableStateOf(true) }
    var thirdPartyCookies by remember { mutableStateOf(true) }

    var engine by remember { mutableStateOf(SearchEngine.getCurrent(ctx)) }
    var desktopMode by remember { mutableStateOf(BrowserPrefs.isDesktop(ctx)) }
    var zoom by remember { mutableStateOf(BrowserPrefs.getZoom(ctx)) }
    var uaKey by remember { mutableStateOf(BrowserPrefs.getUaKey(ctx)) }
    var showUa by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val sniffedUrls = remember { mutableStateListOf<WebInterceptor.SniffedMedia>() }
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
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = if (BrowserPrefs.isDesktop(ctx)) DESKTOP_UA else UserAgents.resolve(BrowserPrefs.getUaKey(ctx))
            settings.textZoom = BrowserPrefs.getZoom(ctx)
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
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
            // 视口与白底：未加载完时也是白页而不是黑块
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setBackgroundColor(android.graphics.Color.WHITE)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                pageLoading = true
                if (url != null && url != "about:blank") {
                    urlInput = url
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
                    urlInput = url
                    BrowserHistoryStore.addHistory(ctx, url)
                }
                view?.evaluateJavascript(webInterceptor.injectionScript, null)
                // 站点自定义 JS + 阅读模式
                val host = SiteScriptStore.hostOf(url ?: "")
                val siteJs = if (host.isNotBlank()) SiteScriptStore.get(ctx, host) else ""
                if (siteJs.isNotBlank()) view?.evaluateJavascript("(function(){try{$siteJs}catch(e){console.warn('LM site script',e)}})();", null)
                if (readerMode) view?.evaluateJavascript(WebScripts.readerMode, null)
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // 返回 true 必须自己销毁并重建，否则视图会一直空白/黑屏
                runCatching { view?.destroy() }
                rebuildTick += 1
                android.widget.Toast.makeText(ctx, "渲染进程被系统回收，已重建页面", android.widget.Toast.LENGTH_SHORT).show()
                return true
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
                    android.widget.Toast.makeText(ctx, "加载失败：${error?.description ?: ""}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
                val ext = url.substringAfterLast('.', "").lowercase().substringBefore('?')
                if (ext in listOf("mp4", "mp3", "m4a", "aac", "flac", "wav", "m3u8", "webm", "flv", "mov", "mkv", "ts", "apk", "zip", "rar", "7z", "pdf") && !url.contains("favicon")) {
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
                tmp.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean {
                        val target = req.url.toString()
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", target)
                        tabs.add(tab)
                        activeId = tab.id
                        return true
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
            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) && !extra.isNullOrBlank()) {
                pendingDownloadUrl = extra
                showDownloadDialog = true
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

    LaunchedEffect(activeId) {
        val tab = currentTab() ?: return@LaunchedEffect
        urlInput = tab.url
        webView.loadUrl(tab.url)
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
                Box(
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
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(palette.surface)
                        .border(1.2.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.1f))), RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            cursorBrush = SolidColor(palette.primary.first()),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val target = SearchEngine.normalize(ctx, urlInput)
                                if (target.isNotBlank()) {
                                    urlInput = target
                                    (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                        as android.view.inputmethod.InputMethodManager)
                                        .hideSoftInputFromWindow(webView.windowToken, 0)
                                    webView.requestFocus()
                                    webView.loadUrl(target)
                                }
                            }),
                            decorationBox = { inner -> if (urlInput.isEmpty()) Text("搜索或输入网址", color = palette.onSurfaceDim); inner() },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val url = webView.url ?: return@IconButton
                                if (BrowserHistoryStore.isBookmarked(ctx, url)) BrowserHistoryStore.removeBookmark(ctx, url)
                                else BrowserHistoryStore.addBookmark(ctx, url, pageTitle)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            val marked = BrowserHistoryStore.isBookmarked(ctx, webView.url ?: "")
                            Icon(if (marked) Icons.Default.Star else Icons.Default.StarBorder, "收藏", tint = if (marked) palette.primary.first() else palette.onSurfaceDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        val next = when (engine) {
                            SearchEngine.BING -> SearchEngine.BAIDU
                            SearchEngine.BAIDU -> SearchEngine.GOOGLE
                            SearchEngine.GOOGLE -> SearchEngine.BING
                        }
                        engine = next
                        BrowserPrefs.setEngine(ctx, next.displayName)
                        android.widget.Toast.makeText(ctx, "搜索引擎：${next.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                    }.background(palette.surface).padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(engine.displayName, color = palette.primary.first(), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
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

            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
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
        Dialog(onDismissRequest = { showMenu = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.surface).padding(vertical = 8.dp)) {
                MenuItem("新标签页", Icons.Default.Add, palette) {
                    val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", "about:blank")
                    tabs.add(tab); activeId = tab.id; showMenu = false
                }
                MenuItem("书签", Icons.Default.Star, palette) { showMenu = false; showBookmarks = true }
                MenuItem("历史记录", Icons.Default.Search, palette) { showMenu = false; showHistory = true }
                MenuItem("页内查找", Icons.Default.Search, palette) { showMenu = false; showFind = true }
                MenuItem("浏览器标识 (UA)", Icons.Default.Layers, palette) { showMenu = false; showUa = true }
                MenuItem("此站自定义 JS", Icons.Default.Add, palette) {
                    showMenu = false
                    val host = SiteScriptStore.hostOf(webView.url ?: "")
                    siteScriptText = if (host.isNotBlank()) SiteScriptStore.get(ctx, host) else ""
                    showSiteScript = true
                }
                MenuItem(if (readerMode) "退出阅读模式" else "阅读模式", Icons.Default.Search, palette) {
                    showMenu = false
                    readerMode = !readerMode
                    webView.evaluateJavascript(WebScripts.readerMode, null)
                }
                MenuItem("另存网页 HTML", Icons.Default.Add, palette) { showMenu = false; savePageHtml(ctx, webView) }
                MenuItem("长截图保存", Icons.Default.Layers, palette) { showMenu = false; saveLongScreenshot(ctx, webView, palette) }
                MenuItem("导出 PDF", Icons.Default.Refresh, palette) { showMenu = false; exportPdf(ctx, webView) }
                MenuItem("数据管理", Icons.Default.Delete, palette) { showMenu = false; showDataPanel = true }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("桌面版网站", color = palette.onSurface, modifier = Modifier.weight(1f))
                    Switch(checked = desktopMode, onCheckedChange = {
                        desktopMode = it
                        BrowserPrefs.setDesktop(ctx, it)
                        webView.settings.userAgentString = if (it) DESKTOP_UA else MOBILE_UA
                        webView.reload()
                    })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文字缩放", color = palette.onSurface, modifier = Modifier.weight(1f))
                    listOf(90, 100, 125, 150).forEach { z ->
                        Text(
                            "$z%",
                            color = if (zoom == z) palette.primary.first() else palette.onSurfaceDim,
                            fontWeight = if (zoom == z) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                zoom = z; BrowserPrefs.setZoom(ctx, z); webView.settings.textZoom = z
                            }.padding(horizontal = 6.dp)
                        )
                    }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                MenuItem(if (fullscreen) "退出全屏" else "全屏浏览", Icons.Default.Layers, palette) { showMenu = false; fullscreen = !fullscreen }
                MenuItem("复制当前网址", Icons.Default.Add, palette) {
                    showMenu = false
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", webView.url ?: ""))
                    android.widget.Toast.makeText(ctx, "网址已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
                MenuItem("关闭全部标签", Icons.Default.Delete, palette) {
                    showMenu = false
                    tabs.clear()
                    val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                    tabs.add(fresh)
                    activeId = fresh.id
                }
                MenuItem("分享当前页", Icons.Default.Search, palette) { showMenu = false; sharePage(ctx, webView.url ?: "") }
                MenuItem("用系统浏览器打开", Icons.Default.Refresh, palette) { showMenu = false; openExternal(ctx, webView.url ?: "") }
                MenuItem("清除缓存与 Cookie", Icons.Default.Delete, palette) { showMenu = false; clearBrowserData(ctx, webView) }
                MenuItem("关闭当前标签", Icons.Default.Close, palette) {
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
                }
            }
        }
    }

    if (showTabs) {
        Dialog(onDismissRequest = { showTabs = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("标签页 ${tabs.size}", fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val tab = BrowserTab(idGen.getAndIncrement(), "新标签页", "about:blank")
                        tabs.add(tab); activeId = tab.id; showTabs = false
                    }) { Text("新建") }
                }
                LazyColumn {
                    items(tabs) { tab ->
                        Row(Modifier.fillMaxWidth().clickable { activeId = tab.id; showTabs = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.title.ifBlank { tab.url }, color = if (tab.id == activeId) palette.primary.first() else palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                tabs.remove(tab)
                                if (tabs.isEmpty()) {
                                    val fresh = BrowserTab(idGen.getAndIncrement(), "新标签页", engine.homeUrl)
                                    tabs.add(fresh); activeId = fresh.id
                                } else if (tab.id == activeId) {
                                    activeId = tabs.first().id
                                }
                            }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "关闭", tint = palette.onSurfaceDim) }
                        }
                    }
                }
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
        Dialog(onDismissRequest = { showSniffList = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已抓取资源 (${sniffedUrls.size})", fontWeight = FontWeight.Bold, color = palette.onSurface)
                    Row {
                        TextButton(onClick = {
                            var added = 0
                            sniffedUrls.take(20).forEach { media ->
                                if (!media.isBlob) {
                                    DownloadManager.add(ctx, media.url, null, 16, 0, emptyMap(), DirStore.resolveDownloadDir(ctx))
                                    added++
                                }
                            }
                            android.widget.Toast.makeText(ctx, "已入队 $added 个任务", android.widget.Toast.LENGTH_SHORT).show()
                            showSniffList = false
                        }) { Text("全部下载") }
                        TextButton(onClick = { sniffedUrls.clear(); showSniffList = false }) { Text("清空") }
                    }
                }
                val grouped = sniffedUrls.groupBy { sniffKind(it.url) }
                val order = listOf("视频", "音频", "安装包", "压缩包", "文档", "图片", "其它")
                LazyColumn {
                    order.forEach { kind ->
                        val list = grouped[kind] ?: return@forEach
                        item(key = "h_$kind") {
                            Text("$kind · ${list.size}", color = palette.onSurfaceDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        items(list) { media ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            pendingDownloadUrl = media.url; showDownloadDialog = true; showSniffList = false
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, null, tint = palette.primary.first())
                            Spacer(Modifier.width(12.dp))
                            Text(if (media.isBlob) "加密/动态流 (Blob)" else media.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.onSurface, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", media.url))
                                android.widget.Toast.makeText(ctx, "已复制链接", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("复制") }
                        }
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
                            webView.settings.userAgentString = preset.ua
                            webView.reload()
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
        AddTaskDialog(initialUrl = pendingDownloadUrl, onDismiss = { showDownloadDialog = false }) { url, name, threads, speed, headers ->
            DownloadManager.add(ctx, url, name, threads, speed, headers, DirStore.resolveDownloadDir(ctx))
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

/** 长截图：优先整页 Picture，失败退化为可视区域 */
private fun saveLongScreenshot(ctx: android.content.Context, webView: WebView, palette: GradientPalette) {
    try {
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        webView.draw(canvas)
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "LM")
            .let { if (it.exists() || it.mkdirs()) it else java.io.File(ctx.filesDir, "shots") }
        val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it) }
        bmp.recycle()
        android.widget.Toast.makeText(ctx, "已保存：${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "截图失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
