package com.downloadsource.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.downloadsource.ThemeStore
import com.downloadsource.core.AudioService
import com.downloadsource.storage.DirStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val VIDEO_EXTS = listOf(
    ".mp4", ".mkv", ".webm", ".mov", ".flv", ".avi", ".m3u8", ".ts", ".m4v", ".rmvb", ".wmv", ".3gp", ".mpg", ".mpeg", ".f4v", ".ogv"
)

private fun isVideoFile(name: String) = VIDEO_EXTS.any { name.endsWith(it, true) }

private val AUDIO_EXTS = listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma", ".ape", ".amr")
private fun isAudioFile(name: String) = AUDIO_EXTS.any { name.endsWith(it, true) }

private fun uniqueDest(dir: File, name: String): File {
    var dest = File(dir, name)
    if (!dest.exists()) return dest
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var i = 1
    while (dest.exists()) {
        val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
        dest = File(dir, candidate)
        i++
        if (i > 200) break
    }
    return dest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScreen(onPlayVideo: (File) -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("全部") }
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("时间") } 
    
    var showVault by remember { mutableStateOf(false) }
    var showVaultAuth by remember { mutableStateOf(false) }
    var showSetVaultPwd by remember { mutableStateOf(false) }
    var newVaultPwd by remember { mutableStateOf("") }
    var vaultPwd by remember { mutableStateOf("") }
    val vaultDirOf = { DirStore.vaultDir(ctx) }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var vaultTick by remember { mutableIntStateOf(0) }
    var vaultPlaying by remember { mutableStateOf<File?>(null) }
    var vaultCount by remember { mutableIntStateOf(0) }

    val filters = listOf("全部", "视频", "音频", "安装包")

    LaunchedEffect(selectedFilter, showVault, vaultTick) {
        withContext(Dispatchers.IO) {
            if (!showVault) {
                val dir = DirStore.resolveDownloadDir(ctx)
                files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            } else {
                val vaultDir = DirStore.vaultDir(ctx)
                vaultFiles = vaultDir.walkTopDown().filter { it.isFile && it.length() > 0 }
                    .sortedByDescending { it.lastModified() }.toList()
                vaultCount = vaultFiles.size
            }
        }
    }

    vaultPlaying?.let { vf ->
        VideoPlayerScreen(uri = android.net.Uri.fromFile(vf), onBack = { vaultPlaying = null; vaultTick++ })
        return
    }

    if (showVault) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showVault = false }) { Icon(Icons.Default.ArrowBack, null, tint = palette.onSurface) }
                Text("🔒 私密保险箱", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = palette.onSurface)
            }
            Spacer(Modifier.height(16.dp))
            if (vaultFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("保险箱空空如也，在下载列表点「移入保险箱」即可藏到这里", color = palette.onSurfaceDim) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vaultFiles) { file ->
                        val isVideo = isVideoFile(file.name)
                        val isAudio = isAudioFile(file.name)
                        val sizeText = when {
                            file.length() >= 1L shl 30 -> String.format("%.2f GB", file.length() / 1073741824.0)
                            file.length() >= 1L shl 20 -> String.format("%.1f MB", file.length() / 1048576.0)
                            file.length() >= 1024 -> "${file.length() / 1024} KB"
                            else -> "${file.length()} B"
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).clickable { vaultPlaying = file }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val thumb = if (isVideo) remember(file.path, file.lastModified()) {
                                runCatching {
                                    if (android.os.Build.VERSION.SDK_INT >= 29)
                                        android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(160, 96), null)
                                    else @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(file.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                                }.getOrNull()
                            } else null
                            Box(Modifier.width(64.dp).height(42.dp).clip(RoundedCornerShape(10.dp)).background(palette.primary.first().copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                if (thumb != null) androidx.compose.foundation.Image(
                                    bitmap = thumb.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                ) else Icon(
                                    when { isVideo -> Icons.Default.PlayArrow; isAudio -> Icons.Default.PlayArrow; else -> Icons.Default.InsertDriveFile },
                                    if (isVideo || isAudio) "播放" else "文件", tint = palette.primary.first()
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, color = palette.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$sizeText · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                val dir = DirStore.resolveDownloadDir(ctx)
                                val dest = uniqueDest(dir, file.name)
                                val ok = file.renameTo(dest) || runCatching { file.copyTo(dest, false); file.delete(); dest.length() > 0 }.getOrDefault(false)
                                if (ok) {
                                    vaultFiles = (vaultDirOf().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList())
                                    vaultTick++
                                    android.widget.Toast.makeText(ctx, "已移出到下载目录", android.widget.Toast.LENGTH_SHORT).show()
                                } else android.widget.Toast.makeText(ctx, "移出失败", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.LockOpen, "移出", tint = palette.primary.first()) }
                            IconButton(onClick = {
                                if (file.delete()) {
                                    vaultFiles = vaultFiles - file
                                    vaultTick++
                                    android.widget.Toast.makeText(ctx, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                                } else android.widget.Toast.makeText(ctx, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.Delete, "删除", tint = palette.onSurfaceDim) }
                        }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("文件库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = palette.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (ThemeStore.getLockPassword(ctx) == null) showSetVaultPwd = true else showVaultAuth = true
            }) {
                androidx.compose.material3.BadgedBox(badge = {
                    if (vaultCount > 0) androidx.compose.material3.Badge { Text(vaultCount.toString()) }
                }) { Icon(Icons.Default.Lock, "私密保险箱", tint = palette.primary.first()) }
            }
            IconButton(onClick = { sortBy = if (sortBy == "时间") "大小" else "时间" }) { Icon(Icons.Default.Sort, null, tint = palette.onSurface) }
        }
        
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim)
            BasicTextField(
                value = searchQuery, onValueChange = { searchQuery = it }, textStyle = TextStyle(color = palette.onSurface),
                cursorBrush = SolidColor(palette.primary.first()), singleLine = true,
                decorationBox = { inner -> if (searchQuery.isEmpty()) Text("搜索文件...", color = palette.onSurfaceDim); inner() },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                val selected = f == selectedFilter
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) palette.primary.first() else palette.surface).clickable { selectedFilter = f }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(f, color = if (selected) Color.White else palette.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        var filteredFiles = files.filter {
            val matchesFilter = when (selectedFilter) {
                "视频" -> isVideoFile(it.name)
                "音频" -> it.name.endsWith(".mp3", true) || it.name.endsWith(".m4a", true)
                "安装包" -> it.name.endsWith(".apk", true)
                else -> true
            }
            val matchesSearch = it.name.contains(searchQuery, true)
            matchesFilter && matchesSearch
        }
        
        filteredFiles = if (sortBy == "时间") filteredFiles.sortedByDescending { it.lastModified() } else filteredFiles.sortedByDescending { it.length() }

        if (filteredFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有找到文件哦 (´･_･`)", color = palette.onSurfaceDim) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredFiles) { file ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface)
                            .clickable {
                                if (isVideoFile(file.name)) {
                                    onPlayVideo(file)
                                } else if (file.name.endsWith(".mp3", true) || file.name.endsWith(".m4a", true)) {
                                    // 修复：真实传递路径给后台音频服务
                                    val intent = Intent(ctx, AudioService::class.java)
                                    intent.putExtra("audio_path", file.absolutePath)
                                    ctx.startService(intent)
                                    android.widget.Toast.makeText(ctx, "后台音频已启动", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = palette.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.length() / 1024 / 1024} MB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(file.lastModified())}", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            // 保险箱目标名必须唯一：旧实现同名直接覆盖，会把保险箱里已有视频冲掉
                            val vaultDir = DirStore.vaultDir(ctx)
                            val dest = uniqueDest(vaultDir, file.name)
                            val ok = file.renameTo(dest) || runCatching {
                                file.copyTo(dest, overwrite = false)
                                file.delete()
                                dest.exists() && dest.length() > 0
                            }.getOrDefault(false)
                            if (ok) {
                                files = files - file
                                vaultFiles = vaultDir.walkTopDown().filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.toList()
                                vaultCount = vaultFiles.size
                                android.widget.Toast.makeText(ctx, "已移入保险箱", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(ctx, "移入失败，文件保持原处", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.Lock, "移入加密箱", tint = palette.onSurfaceDim) }
                        IconButton(onClick = { if (file.delete()) files = files - file }) { Icon(Icons.Default.Delete, null, tint = palette.onSurfaceDim) }
                    }
                }
            }
        }
    }

    if (showSetVaultPwd) {
        Dialog(onDismissRequest = { showSetVaultPwd = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("设置保险箱密码", fontWeight = FontWeight.Bold, color = palette.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("至少 4 位，用于应用锁和保险箱", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = newVaultPwd, onValueChange = { if (it.length <= 12) newVaultPwd = it },
                    label = { Text("新密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    if (newVaultPwd.length < 4) {
                        android.widget.Toast.makeText(ctx, "密码至少 4 位", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        ThemeStore.setLockPassword(ctx, newVaultPwd)
                        newVaultPwd = ""
                        showSetVaultPwd = false
                        vaultTick++
                        showVault = true
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("启用并进入保险箱") }
            }
        }
    }

    if (showVaultAuth) {
        Dialog(onDismissRequest = { showVaultAuth = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("验证密码", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = vaultPwd, onValueChange = { vaultPwd = it }, label = { Text("输入应用锁密码") }, 
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    if (ThemeStore.verifyLockPassword(ctx, vaultPwd)) {
                        vaultTick++
                        vaultTick++
                        showVault = true; showVaultAuth = false; vaultPwd = ""
                    } else android.widget.Toast.makeText(ctx, "密码不对哦 (￣ε(#￣)", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("解锁") }
            }
        }
    }
}
