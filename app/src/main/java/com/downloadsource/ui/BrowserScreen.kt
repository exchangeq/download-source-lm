package com.downloadsource.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.downloadsource.web.SafeWebViewClient
import com.downloadsource.web.WebInterceptor
import java.io.File
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onPlayVideo: (File) -> Unit, onPlayStream: (String) -> Unit,
    triggerSniff: Boolean, onSniffHandled: () -> Unit,
    triggerHistory: Boolean, onHistoryHandled: () -> Unit,
    externalUrlToLoad: String?, onUrlLoaded: () -> Unit
) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    
    // 修复：获取上次退出的URL（如果页面异常关闭）
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }
    
    var currentUrl by remember { mutableStateOf(BrowserHistoryStore.getSession(ctx)) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var pendingDownloadUrl by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }
    
    val sniffedUrls = remember { mutableStateListOf<WebInterceptor.SniffedMedia>() }
    var showSniffList by remember { mutableStateOf(false) }
    val webInterceptor = remember { WebInterceptor() }

    LaunchedEffect(externalUrlToLoad) {
        if (externalUrlToLoad != null) {
            webView.loadUrl(externalUrlToLoad)
            onUrlLoaded()
        }
    }

    LaunchedEffect(Unit) {
        webInterceptor.sniffedData.collect { data ->
            if (sniffedUrls.none { it.url == data.url } && sniffedUrls.size < 100) sniffedUrls.add(data)
        }
    }

    DisposableEffect(Unit) {
        webView.addJavascriptInterface(webInterceptor, "AndroidBridge")
        webView.webViewClient = object : SafeWebViewClient(
            onDownloadRequest = { url -> pendingDownloadUrl = url; showDownloadDialog = true },
            onPlayM3u8 = { url -> onPlayStream(url) }
        ) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) {
                    // 核心修复：页面开始加载时，立刻保存状态，防止意外关闭丢失
                    BrowserHistoryStore.saveSession(ctx, url)
                    urlInput = url
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains("doubleclick.net") || url.contains("googlesyndication.com")) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                val ext = url.substringAfterLast('.', "").lowercase().substringBefore('?')
                // 极限兜底嗅探：包含文本和视频
                if (ext in listOf("mp4", "mp3", "m3u8", "apk", "zip", "ts", "mkv", "avi", "txt", "json", "pdf") && !url.contains("favicon")) {
                    if (sniffedUrls.none { it.url == url } && sniffedUrls.size < 100) {
                        sniffedUrls.add(WebInterceptor.SniffedMedia(url, null, false))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入 JS 深度嗅探
                view?.evaluateJavascript(webInterceptor.injectionScript, null)
            }
        }
        webView.loadUrl(currentUrl)
        onDispose { webView.destroy() }
    }

    // 浏览器 UI 大改版
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部极简液态玻璃搜索栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(palette.surface)
                        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.1f))), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = urlInput, onValueChange = { urlInput = it },
                        textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                        cursorBrush = SolidColor(palette.primary.first()), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val target = com.downloadsource.core.SearchEngine.normalize(
                                urlInput, BrowserPrefs.getEngine(ctx)
                            )
                            if (target.isNotBlank()) {
                                urlInput = target
                                (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                    as android.view.inputmethod.InputMethodManager)
                                    .hideSoftInputFromWindow(webView.windowToken, 0)
                                webView.requestFocus()
                                webView.loadUrl(target)
                            }
                        }),
                        decorationBox = { inner -> if (urlInput.isEmpty()) Text("搜索或输入网址... (●'◡'●)", color = palette.onSurfaceDim); inner() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // WebView
            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())

            // 底部悬浮控制栏 (毛玻璃质感)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp)).background(palette.surface.copy(alpha = 0.9f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(28.dp))
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { webView.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", tint = palette.onSurface) }
                IconButton(onClick = { webView.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进", tint = palette.onSurface) }
                IconButton(onClick = { webView.loadUrl(com.downloadsource.core.SearchEngine.of(BrowserPrefs.getEngine(ctx)).home) }) { Icon(Icons.Default.Home, "主页", tint = palette.onSurface) }
                IconButton(onClick = { webView.reload() }) { Icon(Icons.Default.Refresh, "刷新", tint = palette.onSurface) }
            }
        }

        // 资源嗅探球常驻
        FloatingActionButton(
            onClick = { 
                if (sniffedUrls.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "暂未抓取到资源，请播放视频或刷新 (￣▽￣)", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showSniffList = true 
                }
            },
            containerColor = if (sniffedUrls.isEmpty()) palette.onSurfaceDim.copy(alpha = 0.5f) else palette.primary.first(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 90.dp)
        ) {
            BadgedBox(badge = { if (sniffedUrls.isNotEmpty()) Badge { Text(sniffedUrls.size.toString()) } }) {
                Icon(Icons.Default.Layers, "资源嗅探", tint = Color.White)
            }
        }
    }

    if (showSniffList) {
        Dialog(onDismissRequest = { showSniffList = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已抓取资源 (${sniffedUrls.size}) (★ ω ★)", fontWeight = FontWeight.Bold, color = palette.onSurface)
                    TextButton(onClick = { sniffedUrls.clear(); showSniffList = false }) { Text("清空") }
                }
                LazyColumn {
                    items(sniffedUrls) { media ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { 
                            pendingDownloadUrl = media.url; showDownloadDialog = true; showSniffList = false 
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (media.isBlob) Icons.Default.BubbleChart else Icons.Default.PlayCircle, null, tint = palette.primary.first())
                            Spacer(Modifier.width(12.dp))
                            Text(if (media.isBlob) "加密/动态流 (Blob)" else media.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.onSurface)
                        }
                    }
                }
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
