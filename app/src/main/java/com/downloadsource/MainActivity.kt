package com.downloadsource

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.downloadsource.core.MirrorResolver
import com.downloadsource.core.DownloadManager
import com.downloadsource.core.UpdateManager
import com.downloadsource.storage.BrowserPrefs
import com.downloadsource.storage.DirStore
import com.downloadsource.ui.*
import com.downloadsource.utils.PyRuleEngine
import com.downloadsource.utils.SearchEngine
import java.io.File
import java.net.URLEncoder
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private fun shouldLock(): Boolean {
        val pwd = ThemeStore.getLockPassword(this) ?: return false
        return (System.currentTimeMillis() - ThemeStore.getLastUnlockTime(this)) > 900_000L
    }

    /** Android 11+ 需要「所有文件访问」才能把下载写进公共 Downloads 并让文件库读到 */
    private fun ensureStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (android.os.Environment.isExternalStorageManager()) return
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("授予「所有文件访问」后，下载才能写进公共下载目录，文件库才能扫到已下载文件。")
                .setPositiveButton("去授权") { _, _ ->
                    runCatching {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        ))
                    }.onFailure {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun showDisclaimerIfNeeded() {
        if (com.downloadsource.storage.DisclaimerStore.isAccepted(this)) return
        android.app.AlertDialog.Builder(this)
            .setTitle("使用前请阅读")
            .setMessage(com.downloadsource.storage.DisclaimerStore.TEXT)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ -> com.downloadsource.storage.DisclaimerStore.accept(this) }
            .setNegativeButton("不同意，退出") { _, _ -> finish() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 1001)
        }
        PyRuleEngine.init(this)
        com.downloadsource.storage.UpgradeKeeper.run(this, BuildConfig.VERSION_NAME)
        showDisclaimerIfNeeded()
        ensureStorageAccess()
        setContent {
            val palette = rememberPalette()
            Box(Modifier.fillMaxSize().background(palette.background.first())) {
                GradientBackground(palette) {
                    MaterialTheme(typography = SafeTypography) {
                        var isLocked by remember { mutableStateOf(shouldLock()) }
                        var clipboardUrl by remember { mutableStateOf<String?>(null) }
                        val lastClipUrl = remember { mutableStateOf("") }
                        
                        LifecycleResumeEffect(Unit) {
                            if (shouldLock()) isLocked = true
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (text != null && (text.startsWith("http") || text.contains("pan.quark"))) {
                                if (text != lastClipUrl.value) {
                                    clipboardUrl = text
                                    lastClipUrl.value = text
                                }
                            }
                            onPauseOrDispose {}
                        }

                        if (isLocked) { 
                            SecureLockScreen(onUnlock = { isLocked = false }) 
                        } else {
                            var updateInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
                            LaunchedEffect(Unit) { updateInfo = UpdateManager.checkUpdate(BuildConfig.VERSION_NAME) }
                            
                            if (clipboardUrl != null) {
                                AlertDialog(
                                    onDismissRequest = { clipboardUrl = null },
                                    title = { Text("检测到剪贴板链接 (★ ω ★)") },
                                    text = { Text("发现: ${clipboardUrl?.take(30)}...\n是否立即下载？") },
                                    confirmButton = { TextButton(onClick = { 
                                        DownloadManager.add(this@MainActivity, clipboardUrl!!, null, 8, 0, emptyMap(), DirStore.resolveDownloadDir(this@MainActivity))
                                        clipboardUrl = null 
                                    }) { Text("立即下载 (๑•̀ㅂ•́)و✧") } },
                                    dismissButton = { TextButton(onClick = { clipboardUrl = null }) { Text("忽略") } }
                                )
                            }
                            
                            if (updateInfo != null) { 
                                val ctx = LocalContext.current
                                AlertDialog(
                                    onDismissRequest = { updateInfo = null }, 
                                    title = { Text("发现新版本 v${updateInfo?.version} (●'◡'●)") }, 
                                    text = { Text(updateInfo?.changelog ?: "") }, 
                                    confirmButton = { 
                                        TextButton(onClick = { 
                                            updateInfo?.let { UpdateManager.downloadAndInstall(ctx, it) }
                                            updateInfo = null 
                                        }) { Text("立即更新") } 
                                    },
                                    dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("稍后") } }
                                )
                            }
                            
                            MainScaffold()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecureLockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    var inputPwd by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var useText by remember { mutableStateOf(false) }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "DEL")
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    val pwdLen = remember { ThemeStore.getLockPassword(ctx)?.length ?: 0 }

    fun submit() {
        if (ThemeStore.verifyLockPassword(ctx, inputPwd)) {
            onUnlock()
        } else {
            errorMsg = "密码错误，请重试"
            inputPwd = ""
        }
    }

    LaunchedEffect(errorMsg) {
        if (errorMsg.isNotEmpty()) {
            repeat(2) {
                shake.animateTo(-10f, androidx.compose.animation.core.tween(90))
                shake.animateTo(10f, androidx.compose.animation.core.tween(90))
            }
            shake.animateTo(0f, androidx.compose.animation.core.tween(90))
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(palette.background))) {
        // 背景光斑
        Box(Modifier.size(320.dp).offset(x = (-90).dp, y = (-60).dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(palette.primary.first().copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.size(280.dp).align(Alignment.BottomEnd).offset(x = 90.dp, y = 80.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(palette.primary.last().copy(alpha = 0.28f), Color.Transparent))))

        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                .fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(palette.surface.copy(alpha = 0.86f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(palette.primary)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(30.dp), tint = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Text("应用已锁定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.onSurface)
            Text(if (useText) "请输入密码" else "输入密码解锁 LM", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(18.dp))

            if (useText) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.background.first().copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = inputPwd,
                        onValueChange = { if (it.length <= 32) { inputPwd = it; errorMsg = "" } },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = palette.onSurface, fontSize = 18.sp),
                        cursorBrush = SolidColor(palette.primary.first()),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { submit() }) { Icon(Icons.Default.ArrowForward, "解锁", tint = palette.primary.first()) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(6) { idx ->
                        val filled = idx < inputPwd.length
                        Box(
                            Modifier.size(if (filled) 14.dp else 11.dp).clip(CircleShape)
                                .background(if (filled) palette.primary.first() else palette.onSurfaceDim.copy(alpha = 0.3f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(errorMsg, color = if (errorMsg.isEmpty()) Color.Transparent else Color(0xFFE53935), fontSize = 13.sp)

            if (!useText) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    keys.chunked(3).forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            rowKeys.forEach { key ->
                                Box(
                                    Modifier.size(68.dp).clip(CircleShape)
                                        .background(if (key.isEmpty()) Color.Transparent else palette.background.first().copy(alpha = 0.45f))
                                        .border(if (key.isEmpty()) 0.dp else 1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                                        .then(if (key.isEmpty()) Modifier else Modifier.clickable {
                                            errorMsg = ""
                                            when {
                                                key == "DEL" -> if (inputPwd.isNotEmpty()) inputPwd = inputPwd.dropLast(1)
                                                inputPwd.length < 32 -> {
                                                    inputPwd += key
                                                    // 位数达到真实密码长度时自动校验，少按一次
                                                    if (pwdLen in 1..12 && inputPwd.length >= pwdLen) submit()
                                                }
                                            }
                                        }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        key == "DEL" -> Icon(Icons.Default.Backspace, "删除", tint = palette.onSurface)
                                        key.isEmpty() -> Unit
                                        else -> Text(key, fontSize = 23.sp, fontWeight = FontWeight.Medium, color = palette.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { submit() },
                    modifier = Modifier.fillMaxWidth(0.62f).height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) { Text("解锁") }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (useText) "改用数字键盘" else "密码含字母？改用文字输入",
                color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { useText = !useText; inputPwd = ""; errorMsg = "" }.padding(8.dp)
            )
        }
    }
}

enum class Tab { DOWNLOAD, BROWSER, LIBRARY }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold() {
    val ctx = LocalContext.current // 提前获取，解决悬浮球菜单上下文问题
    var currentTab by remember { mutableStateOf(Tab.DOWNLOAD) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var browserUrlToLoad by remember { mutableStateOf<String?>(null) }

    if (playingUri != null) {
        VideoPlayerScreen(uri = playingUri!!, onBack = { playingUri = null })
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = LocalPalette.current.surface) {
                    NavigationBarItem(selected = currentTab == Tab.DOWNLOAD, onClick = { currentTab = Tab.DOWNLOAD }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("下载") })
                    NavigationBarItem(selected = currentTab == Tab.BROWSER, onClick = { currentTab = Tab.BROWSER }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("搜索") })
                    NavigationBarItem(selected = currentTab == Tab.LIBRARY, onClick = { currentTab = Tab.LIBRARY }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("文件库") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (currentTab) {
                    Tab.DOWNLOAD -> PageMotion(0) {
                        DownloadScreen(onOpenBrowserUrl = { url -> browserUrlToLoad = url; currentTab = Tab.BROWSER })
                    }
                    Tab.BROWSER -> PageMotion(1) {
                        BrowserScreen(
                            onPlayVideo = { playingUri = Uri.fromFile(it) },
                            onPlayStream = { playingUri = Uri.parse(it) },
                            externalUrlToLoad = browserUrlToLoad,
                            onUrlLoaded = { browserUrlToLoad = null }
                        )
                    }
                    Tab.LIBRARY -> PageMotion(2) {
                        MediaLibraryScreen(onPlayVideo = { playingUri = Uri.fromFile(it) })
                    }
                }
            }
        }
        
        // 悬浮球
        var offsetX by remember { mutableFloatStateOf(screenWidthPx / 2) }
        var offsetY by remember { mutableFloatStateOf(screenHeightPx - 400f) }
        var showMenu by remember { mutableStateOf(false) }
        val ballSize = 48.dp
        val ballSizePx = with(density) { ballSize.toPx() }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(ballSize)
                .alpha(if (showMenu) 1f else 0.8f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { showMenu = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - ballSizePx)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - ballSizePx)
                        }
                    )
                }
                .clip(CircleShape)
                .background(LocalPalette.current.primary.first())
                .combinedClickable(onClick = { showMenu = !showMenu }, onLongClick = { showMenu = true }),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Menu, "Menu", tint = Color.White)
        }

        if (showMenu) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                offset = DpOffset(with(density) { offsetX.toDp() }, with(density) { offsetY.toDp() })
            ) {
                DropdownMenuItem(text = { Text("下载列表 (●'◡'●)") }, leadingIcon = { Icon(Icons.Default.Home, null) }, onClick = { currentTab = Tab.DOWNLOAD; showMenu = false })
                DropdownMenuItem(text = { Text("浏览器 (★ ω ★)") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { currentTab = Tab.BROWSER; showMenu = false })
                DropdownMenuItem(text = { Text("文件库 (￣▽￣)") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { currentTab = Tab.LIBRARY; showMenu = false })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("设置 (๑•̀ㅂ•́)و✧") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { 
                    showMenu = false
                    ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                })
            }
        }
    }
}

@Composable
fun DownloadScreen(onOpenBrowserUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    val tasks by DownloadManager.tasks.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillUrl by remember { mutableStateOf("") }
    var dirPath by remember { mutableStateOf(DirStore.resolveDownloadDir(ctx).absolutePath) }
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("全部") }
    var sortMode by remember { mutableStateOf("最新") }
    var showBulk by remember { mutableStateOf(false) }
    val filters = listOf("全部", "下载中", "排队中", "已完成", "失败")
    
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedTasks = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    var autoMirror by remember { mutableStateOf(true) }

    val shortcuts = listOf(
        Pair("哔哩哔哩", "https://m.bilibili.com"),
        Pair("知乎", "https://www.zhihu.com"),
        Pair("GitHub", "https://github.com"),
        Pair("百度", "https://www.baidu.com")
    )

    val doSearch = {
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            keyboardController?.hide()
            if (q.startsWith("http") || q.contains(".com") || q.contains(".cn") || q.contains("pan.") || q.contains("lanzou")) {
                DownloadManager.add(ctx, q, null, 8, 0, emptyMap(), File(dirPath))
                searchQuery = ""
            } else {
                val engine = SearchEngine.getCurrent(ctx)
                onOpenBrowserUrl(engine.baseUrl + URLEncoder.encode(q, "UTF-8"))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isSelectionMode) "已选择 ${selectedTasks.size} 项" else "Download source", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = palette.onSurface)
                        if (!isSelectionMode) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.background(if (BuildConfig.CHANNEL == "zeta") Color.Red else Color(0xFF3B82F6), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text(if (BuildConfig.CHANNEL == "zeta") "内测" else "发行", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val totalSpeed = tasks.filter { it.status == com.downloadsource.core.TaskStatus.Running }.sumOf { it.speed }
                    Text(
                        "${BuildConfig.CHANNEL.uppercase()} · v${BuildConfig.VERSION_NAME}" +
                            if (totalSpeed > 0) " · ${String.format("%.1f", totalSpeed / 1024.0 / 1024.0)} MB/s" else "",
                        color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall
                    )
                }
                
                if (isSelectionMode) {
                    // 多选：一键移入保险箱
                    IconButton(onClick = {
                        selectedTasks.forEach { DownloadManager.moveToVault(ctx, it) }
                        selectedTasks.clear(); isSelectionMode = false
                    }) { Icon(Icons.Default.Lock, "移入保险箱 (๑•̀ㅂ•́)و✧", tint = palette.primary.first()) }
                    IconButton(onClick = {
                        selectedTasks.forEach { DownloadManager.remove(ctx, it) }
                        selectedTasks.clear(); isSelectionMode = false
                    }) { Icon(Icons.Default.Delete, "删除", tint = Color.Red) }
                    IconButton(onClick = { isSelectionMode = false; selectedTasks.clear() }) { Icon(Icons.Default.Close, "取消", tint = palette.onSurface) }
                } else {
                    if (tasks.isNotEmpty()) IconButton(onClick = { isSelectionMode = true }) { Icon(Icons.Default.Checklist, "多选", tint = palette.onSurface) }
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, NetDiskActivity::class.java)) }) { Icon(Icons.Default.Cloud, "网盘", tint = palette.primary.first()) }
                    if (tasks.isNotEmpty()) IconButton(onClick = { showBulk = true }) { Icon(Icons.Default.MoreVert, "批量与排序", tint = palette.onSurface) }
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) { Icon(Icons.Default.Settings, "设置", tint = palette.onSurface) }
                }
            }
            Spacer(Modifier.height(16.dp))
            
            if (!isSelectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.surface).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim)
                    BasicTextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, textStyle = TextStyle(color = palette.onSurface),
                        cursorBrush = SolidColor(palette.primary.first()), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { doSearch() }),
                        decorationBox = { inner -> if (searchQuery.isEmpty()) Text("输入链接或搜索关键词... (●'◡'●)", color = palette.onSurfaceDim); inner() },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { doSearch() }) { Icon(Icons.Default.ArrowForward, null, tint = palette.primary.first()) }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shortcuts) { item ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { 
                            onOpenBrowserUrl(item.second) 
                        }.padding(8.dp)) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(palette.primary.first().copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                Text(item.first.take(1), color = palette.primary.first(), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(item.first, style = MaterialTheme.typography.bodySmall, color = palette.onSurface)
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { f ->
                        val selected = filterMode == f
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) palette.primary.first() else palette.surface).clickable { filterMode = f }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(f, color = if (selected) Color.White else palette.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            
            val filteredTasks = tasks.filter { 
                when(filterMode) {
                    "下载中" -> it.status == com.downloadsource.core.TaskStatus.Running
                    "排队中" -> it.status == com.downloadsource.core.TaskStatus.Pending
                    "已完成" -> it.status == com.downloadsource.core.TaskStatus.Completed
                    "失败" -> it.status == com.downloadsource.core.TaskStatus.Failed
                    else -> true
                }
            }

            val sortedTasks = when (sortMode) {
                "大小" -> filteredTasks.sortedByDescending { it.totalBytes }
                "进度" -> filteredTasks.sortedByDescending { it.fraction }
                else -> filteredTasks.sortedByDescending { it.createdAt }
            }

            if (filteredTasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无任务呢，雑魚 (￣▽￣)~*", color = palette.onSurfaceDim) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(sortedTasks, key = { it.id }) { task ->
                        if (isSelectionMode) {
                            Row(Modifier.fillMaxWidth().clickable {
                                if (selectedTasks.contains(task.id)) selectedTasks.remove(task.id) else selectedTasks.add(task.id)
                            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selectedTasks.contains(task.id), onCheckedChange = { 
                                    if (it) selectedTasks.add(task.id) else selectedTasks.remove(task.id) 
                                })
                                DownloadCard(task = task, onPause = {}, onResume = {}, onDelete = {}, onOpen = {})
                            }
                        } else {
                            DownloadCard(
                                task = task, 
                                onPause = { DownloadManager.pause(ctx, task.id) }, 
                                onResume = { DownloadManager.start(ctx, task.id) }, 
                                onDelete = { DownloadManager.remove(ctx, task.id) },
                                onOpen = { openDownloadedFile(ctx, task) }
                            )
                        }
                    }
                }
            }
        }

        if (!isSelectionMode) {
            FloatingActionButton(
                onClick = { showAddDialog = true; prefillUrl = "" },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = palette.primary.first(),
                shape = CircleShape
            ) { Text("+", style = MaterialTheme.typography.headlineMedium, color = Color.White) }
        }
    }

    if (showBulk) {
        Dialog(onDismissRequest = { showBulk = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.surface).padding(16.dp)) {
                Text("批量操作与排序", fontWeight = FontWeight.Bold, color = palette.onSurface)
                Spacer(Modifier.height(8.dp))
                listOf("最新", "大小", "进度").forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { sortMode = mode }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("排序：$mode", color = if (sortMode == mode) palette.primary.first() else palette.onSurface, modifier = Modifier.weight(1f))
                        if (sortMode == mode) Text("当前", color = palette.primary.first(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("同时下载", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    listOf(3, 5, 8).forEach { n ->
                        Box(
                            Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50))
                                .background(if (DownloadManager.MAX_CONCURRENT == n) palette.primary.first() else palette.onSurface.copy(alpha = 0.08f))
                                .clickable { DownloadManager.MAX_CONCURRENT = n; showBulk = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("$n", color = if (DownloadManager.MAX_CONCURRENT == n) Color.White else palette.onSurface, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("GitHub 自动加速", color = palette.onSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = MirrorResolver.isAutoGithub(ctx), onCheckedChange = {
                        MirrorResolver.setAutoGithub(ctx, it)
                        autoMirror = it
                    })
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = MirrorResolver.healthCheck(ctx, DownloadManager.httpClient)
                            android.widget.Toast.makeText(ctx, "可用节点：$ok", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("镜像测速") }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    TextButton(onClick = { tasks.forEach { DownloadManager.pause(ctx, it.id) }; showBulk = false }) { Text("全部暂停") }
                    TextButton(onClick = { tasks.filter { it.status != com.downloadsource.core.TaskStatus.Completed }.forEach { DownloadManager.start(ctx, it.id) }; showBulk = false }) { Text("全部继续") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        tasks.filter { it.status == com.downloadsource.core.TaskStatus.Completed }.forEach { DownloadManager.remove(ctx, it.id) }
                        showBulk = false
                    }) { Text("清除已完成", color = Color.Red) }
                    TextButton(onClick = { showBulk = false }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("关闭") }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(initialUrl = prefillUrl, onDismiss = { showAddDialog = false }) { url, name, threads, speed, headers ->
            DownloadManager.add(ctx, url, name, threads, speed, headers, File(dirPath))
            showAddDialog = false
        }
    }
}

/** 已完成任务点击：apk 走安装器，其它走系统打开方式 */
private fun openDownloadedFile(ctx: android.content.Context, task: com.downloadsource.core.DownloadTask) {
    val path = task.outputPath ?: return
    val file = java.io.File(path)
    if (!file.exists()) {
        android.widget.Toast.makeText(ctx, "文件已被移动或删除", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val mime = when (file.extension.lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "mp4", "mkv", "webm", "avi", "mov", "ts" -> "video/*"
            "mp3", "flac", "m4a", "wav", "ogg", "aac" -> "audio/*"
            "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
            "pdf" -> "application/pdf"
            "zip", "rar", "7z", "gz", "tar" -> "application/octet-stream"
            else -> "*/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, "打开文件"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "没有可用的打开方式", android.widget.Toast.LENGTH_SHORT).show()
    }
}
