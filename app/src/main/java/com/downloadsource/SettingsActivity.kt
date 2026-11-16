package com.downloadsource

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.downloadsource.core.Aria2Manager
import com.downloadsource.core.DownloadManager
import com.downloadsource.core.LocalWebServer
import com.downloadsource.core.UpdateManager
import com.downloadsource.storage.BrowserPrefs
import com.downloadsource.storage.DirStore
import com.downloadsource.ui.*
import com.downloadsource.utils.PoemEasterEgg
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = rememberPalette()
            GradientBackground(palette) { MaterialTheme { SettingsScreen(onClose = { finish() }) } }
        }
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    
    // 各状态
    var themeStyle by remember { mutableIntStateOf(ThemeStore.getThemeStyle(ctx)) }
    var engine by remember { mutableStateOf(BrowserPrefs.getEngine(ctx)) }
    var snifferType by remember { mutableIntStateOf(BrowserPrefs.getSnifferType(ctx)) }
    var currentLock by remember { mutableStateOf(ThemeStore.getLockPassword(ctx)) }
    var showPwdDialog by remember { mutableStateOf(false) }
    var inputPwd by remember { mutableStateOf("") }
    var serverIp by remember { mutableStateOf(if (LocalWebServer.isRunning) "已开启" else "未启动") }
    
    // 弹窗与彩蛋
    var versionClickCount by remember { mutableIntStateOf(0) }
    var showPoem by remember { mutableStateOf(false) }
    var poemText by remember { mutableStateOf("") }
    var showAria2Dialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏与彩蛋触发点
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Back", tint = palette.onSurface, modifier = Modifier.rotate(180f)) }
            Text("设置 (v${BuildConfig.VERSION_NAME})", modifier = Modifier.clickable { 
                versionClickCount++
                if (versionClickCount >= 10) { 
                    poemText = PoemEasterEgg.getRandomPoem()
                    showPoem = true
                    versionClickCount = 0 
                }
            }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = palette.onSurface)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            
            // 1. 检查更新
            SettingsGroup {
                SettingsRow("检查更新", if (checking) "检测中... (。_。)" else "检查最新版", Icons.Default.Update) {
                    if (!checking) { 
                        checking = true; 
                        scope.launch { 
                            val info = UpdateManager.checkUpdate(BuildConfig.VERSION_NAME.substringBefore("-"))
                            checking = false; 
                            if (info != null) {
                                UpdateManager.showUpdateDialog(ctx, info)
                            } else {
                                Toast.makeText(ctx, "当前已是最新版，雑魚~ (●'◡'●)", Toast.LENGTH_SHORT).show() 
                            }
                        } 
                    }
                }
            }

            // 2. 外观与引擎 (液态玻璃按钮)
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("主题风格", color = palette.onSurface, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("默认", "粉白", "极光", "日落", "深海", "赛博").forEachIndexed { index, name ->
                        Box(Modifier.clip(RoundedCornerShape(50))
                            .background(if (themeStyle == index) Brush.linearGradient(palette.primary) else Brush.linearGradient(listOf(palette.surface, palette.surface)))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .clickable { themeStyle = index; ThemeStore.setThemeStyle(ctx, index) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(name, color = if (themeStyle == index) Color.White else palette.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索引擎", color = palette.onSurface, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Bing", "Baidu", "Google").forEach { e ->
                        Box(Modifier.clip(RoundedCornerShape(50))
                            .background(if (engine == e) Brush.linearGradient(palette.primary) else Brush.linearGradient(listOf(palette.surface, palette.surface)))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .clickable { engine = e; BrowserPrefs.setEngine(ctx, e) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(e, color = if (engine == e) Color.White else palette.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("资源嗅探引擎", color = palette.onSurface, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JS 注入", "Python 拦截").forEachIndexed { index, name ->
                        Box(Modifier.clip(RoundedCornerShape(50))
                            .background(if (snifferType == index) Brush.linearGradient(palette.primary) else Brush.linearGradient(listOf(palette.surface, palette.surface)))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .clickable { snifferType = index; BrowserPrefs.setSnifferType(ctx, index) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(name, color = if (snifferType == index) Color.White else palette.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 3. 极客与远程
            SettingsGroup {
                SettingsRow("镜像源管理", "加速节点 (๑•̀ㅂ•́)و✧", Icons.Default.Link) { ctx.startActivity(Intent(ctx, SourceManageActivity::class.java)) }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                SettingsRow("油猴脚本", "注入 JS 脚本", Icons.Default.Code) { ctx.startActivity(Intent(ctx, TampermonkeyActivity::class.java)) }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                SettingsRow("Aria2 远程下载", Aria2Manager.getServer(ctx).ifBlank { "未配置" }, Icons.Default.Router) { showAria2Dialog = true }
            }

            // 4. 安全与保护
            SettingsGroup {
                SettingsRow("应用锁 (15分钟免密)", if (currentLock != null) "已开启" else "未开启", Icons.Default.Lock) { showPwdDialog = true }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                SettingsRow("防杀后台保护", "加入电池白名单", Icons.Default.BatteryChargingFull) {
                    try { ctx.startActivity(Intent().apply { action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; data = Uri.parse("package:${ctx.packageName}") }) } catch (e: Exception) { Toast.makeText(ctx, "无法启动电池优化 (´･_･`)", Toast.LENGTH_SHORT).show() }
                }
            }

            // 5. 工具
            SettingsGroup {
                SettingsRow("局域网互传", serverIp, Icons.Default.Wifi) {
                    if (LocalWebServer.isRunning) {
                        LocalWebServer.stop(); serverIp = "未启动"; Toast.makeText(ctx, "服务器已关闭 (￣▽￣)", Toast.LENGTH_SHORT).show()
                    } else {
                        serverIp = LocalWebServer.start(ctx); Toast.makeText(ctx, "请在电脑访问: $serverIp (●'◡'●)", Toast.LENGTH_LONG).show()
                    }
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                SettingsRow("清理下载缓存", "清理临时分片", Icons.Default.DeleteSweep) {
                    val dir = DirStore.resolveDownloadDir(ctx)
                    var count = 0
                    dir.listFiles()?.filter { it.name.startsWith(".") && it.isDirectory }?.forEach { it.deleteRecursively(); count++ }
                    Toast.makeText(ctx, "已清理 $count 个缓存 (๑•̀ㅂ•́)و✧", Toast.LENGTH_SHORT).show()
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
                SettingsRow("清空下载记录", "不删除本地文件", Icons.Default.Delete) {
                    DownloadManager.tasks.value.forEach { DownloadManager.remove(ctx, it.id) }
                    Toast.makeText(ctx, "记录已清空，雑魚~ (￣▽￣)", Toast.LENGTH_SHORT).show()
                }
            }
            
            // 6. 关于
            SettingsGroup {
                Row(Modifier.fillMaxWidth().clickable { 
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Maxx-EX/download-source"))) 
                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painter = painterResource(id = R.drawable.ic_github), contentDescription = "GitHub", modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("GitHub 仓库", color = palette.onSurface, fontWeight = FontWeight.Medium)
                        Text("Maxx-EX/download-source", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = palette.onSurfaceDim)
                }
                HorizontalDivider(color = palette.onSurface.copy(alpha = 0.1f))
            }
            
            Spacer(Modifier.height(24.dp))
        }
    }

    // 弹窗区域
    if (showPoem) {
        AlertDialog(onDismissRequest = { showPoem = false }, title = { Text("发现隐藏内容 🎉 (★ ω ★)") }, text = { Text(poemText) }, confirmButton = { TextButton(onClick = { showPoem = false }) { Text("好诗") } })
    }
    
    if (showAria2Dialog) {
        var inputUrl by remember { mutableStateOf(Aria2Manager.getServer(ctx)) }
        AlertDialog(
            onDismissRequest = { showAria2Dialog = false },
            title = { Text("配置 Aria2 RPC") },
            text = { OutlinedTextField(value = inputUrl, onValueChange = { inputUrl = it }, placeholder = { Text("http://192.168.1.100:6800/jsonrpc") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Aria2Manager.setServer(ctx, inputUrl); showAria2Dialog = false }) { Text("保存") } }
        )
    }

    if (showPwdDialog) {
        AlertDialog(
            onDismissRequest = { showPwdDialog = false },
            title = { Text(if (currentLock == null) "设置应用锁" else "关闭应用锁") },
            text = {
                if (currentLock == null) {
                    OutlinedTextField(value = inputPwd, onValueChange = { inputPwd = it }, label = { Text("输入密码 (至少4位)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                } else Text("确认要清除应用锁密码吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (currentLock == null) {
                        if (inputPwd.length >= 4) { ThemeStore.setLockPassword(ctx, inputPwd); currentLock = inputPwd; showPwdDialog = false } else Toast.makeText(ctx, "密码至少4位啦，雑魚 (｀・ω・´)", Toast.LENGTH_SHORT).show()
                    } else { ThemeStore.setLockPassword(ctx, null); currentLock = null; showPwdDialog = false }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPwdDialog = false }) { Text("取消") } }
        )
    }

}

@Composable fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) { 
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalPalette.current.surface).border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)), content = content) 
}

@Composable fun SettingsRow(title: String, subtitle: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = palette.onSurface, modifier = Modifier.padding(end = 12.dp))
        Text(title, color = palette.onSurface, modifier = Modifier.weight(1f))
        Text(subtitle, color = palette.onSurfaceDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = palette.onSurfaceDim, modifier = Modifier.padding(start = 8.dp))
    }
}
