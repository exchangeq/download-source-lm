package com.downloadsource.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.sp
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

private fun fileExt(name: String): String {
    val idx = name.lastIndexOf('.')
    return if (idx > 0) name.substring(idx) else ""
}

private fun mimeOf(name: String): String = when (fileExt(name).lowercase()) {
    ".mp4", ".mkv", ".webm", ".mov", ".flv", ".m4v", ".avi" -> "video/*"
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg" -> "audio/*"
    ".jpg", ".jpeg", ".png", ".gif", ".webp" -> "image/*"
    ".apk" -> "application/vnd.android.package-archive"
    ".zip" -> "application/zip"
    ".pdf" -> "application/pdf"
    else -> "*/*"
}

/** 保险箱里的非视频文件：FileProvider 交给系统应用打开，避免 file:// 在 Android 7+ 崩溃 */
private fun openVaultFile(ctx: android.content.Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file.name))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(ctx, "没有可打开该文件的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(size: Long): String = when {
    size >= 1L shl 30 -> String.format("%.2f GB", size / 1073741824.0)
    size >= 1L shl 20 -> String.format("%.1f MB", size / 1048576.0)
    size >= 1024 -> "${size / 1024} KB"
    else -> "$size B"
}

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
    var vaultItems by remember { mutableStateOf<List<com.downloadsource.storage.VaultItem>>(emptyList()) }
    var vaultTick by remember { mutableIntStateOf(0) }
    var vaultPlaying by remember { mutableStateOf<File?>(null) }
    var vaultPlayingKey by remember { mutableStateOf("") }
    var vaultCount by remember { mutableIntStateOf(0) }
    var vaultSearch by remember { mutableStateOf("") }
    var vaultSort by remember { mutableStateOf("时间") }
    var vaultMenuFor by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<com.downloadsource.storage.VaultItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    val filters = listOf("全部", "视频", "音频", "安装包")

    LaunchedEffect(selectedFilter, showVault, vaultTick) {
        withContext(Dispatchers.IO) {
            if (!showVault) {
                val dir = DirStore.resolveDownloadDir(ctx)
                files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            } else {
                vaultItems = com.downloadsource.storage.VaultRepo.list(ctx)
                vaultCount = vaultItems.size
            }
        }
    }

    vaultPlaying?.let { vf ->
        VideoPlayerScreen(uri = android.net.Uri.fromFile(vf), key = vaultPlayingKey, onBack = {
            runCatching { vf.delete() }
            runCatching { vf.parentFile?.listFiles()?.forEach { it.delete() } }
            vaultPlaying = null; vaultTick++
        })
        return
    }

    fun openVaultItem(item: com.downloadsource.storage.VaultItem, play: Boolean) {
        val tmp = com.downloadsource.storage.VaultRepo.decryptToCache(ctx, item)
        if (tmp == null) {
            android.widget.Toast.makeText(ctx, "解密失败，文件可能已损坏", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (play) { vaultPlayingKey = item.id; vaultPlaying = tmp } else openVaultFile(ctx, tmp)
    }

    if (showVault) {
        BackHandler { showVault = false }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 40.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { showVault = false }) { Icon(Icons.Default.ArrowBack, "返回", tint = palette.onSurface) }
                Column(Modifier.weight(1f)) {
                    Text("私密保险箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = palette.onSurface)
                    Text("${vaultItems.size} 个文件 · AES-256 加密存储", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vaultTick++ }) { Icon(Icons.Default.Refresh, "刷新", tint = palette.onSurface) }
                IconButton(onClick = { vaultSort = if (vaultSort == "时间") "大小" else "时间" }) { Icon(Icons.Default.Sort, "排序：$vaultSort", tint = palette.onSurface) }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = palette.onSurfaceDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = vaultSearch, onValueChange = { vaultSearch = it }, singleLine = true,
                    textStyle = TextStyle(color = palette.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(palette.primary.first()),
                    decorationBox = { inner -> Box { if (vaultSearch.isEmpty()) Text("搜索保险箱文件", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall); inner() } },
                    modifier = Modifier.weight(1f)
                )
                if (vaultSearch.isNotEmpty()) Icon(
                    Icons.Default.Close, "清空", tint = palette.onSurfaceDim,
                    modifier = Modifier.size(18.dp).clickable { vaultSearch = "" }
                )
            }
            Spacer(Modifier.height(10.dp))
            val shown = vaultItems.filter { vaultSearch.isBlank() || it.name.contains(vaultSearch, true) }
                .let { if (vaultSort == "大小") it.sortedByDescending { i -> i.size } else it }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, tint = palette.onSurfaceDim, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (vaultItems.isEmpty()) "保险箱空空如也\n在下载列表点「移入保险箱」即可加密藏到这里" else "没有匹配的文件",
                            color = palette.onSurfaceDim, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { item ->
                        val isVideo = isVideoFile(item.name)
                        val isAudio = isAudioFile(item.name)
                        val sizeText = formatBytes(item.size)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface)
                                .clickable { openVaultItem(item, isVideo || isAudio) }
                                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.width(72.dp).height(46.dp).clip(RoundedCornerShape(10.dp))
                                    .background(palette.primary.first().copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when { isVideo -> Icons.Default.PlayArrow; isAudio -> Icons.Default.PlayArrow; else -> Icons.Default.InsertDriveFile },
                                    if (isVideo || isAudio) "播放" else "文件", tint = palette.primary.first()
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = palette.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "$sizeText · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.addedAt))} · 已加密",
                                    color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Box {
                                IconButton(onClick = { vaultMenuFor = item.id }) {
                                    Icon(Icons.Default.MoreVert, "更多", tint = palette.onSurfaceDim)
                                }
                                DropdownMenu(expanded = vaultMenuFor == item.id, onDismissRequest = { vaultMenuFor = null }) {
                                    DropdownMenuItem(text = { Text("播放") }, onClick = { vaultMenuFor = null; openVaultItem(item, true) })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                        vaultMenuFor = null; renameItem = item; renameText = item.name
                                    })
                                    DropdownMenuItem(text = { Text("移出到下载目录") }, onClick = {
                                        vaultMenuFor = null
                                        val out = com.downloadsource.storage.VaultRepo.export(ctx, item, DirStore.resolveDownloadDir(ctx))
                                        android.widget.Toast.makeText(ctx, if (out != null) "已解密移出：${out.name}" else "移出失败", android.widget.Toast.LENGTH_SHORT).show()
                                        vaultTick++
                                    })
                                    DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = {
                                        vaultMenuFor = null
                                        val tmp = com.downloadsource.storage.VaultRepo.decryptToCache(ctx, item)
                                        if (tmp != null) openVaultFile(ctx, tmp) else android.widget.Toast.makeText(ctx, "解密失败", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                    DropdownMenuItem(text = { Text("分享") }, onClick = {
                                        vaultMenuFor = null
                                        val tmp = com.downloadsource.storage.VaultRepo.decryptToCache(ctx, item)
                                        if (tmp != null) runCatching {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", tmp)
                                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = mimeOf(item.name); putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            ctx.startActivity(android.content.Intent.createChooser(share, "分享"))
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("删除", color = Color(0xFFFF5A5A)) }, onClick = {
                                        vaultMenuFor = null
                                        com.downloadsource.storage.VaultRepo.delete(ctx, item)
                                        android.widget.Toast.makeText(ctx, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                                        vaultTick++
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        renameItem?.let { item ->
            Dialog(onDismissRequest = { renameItem = null }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(18.dp)) {
                    Text("重命名", fontWeight = FontWeight.Bold, color = palette.onSurface)
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = renameText, onValueChange = { renameText = it }, singleLine = true,
                        textStyle = TextStyle(color = palette.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(palette.primary.first()),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(palette.background.first().copy(alpha = 0.5f)).padding(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renameItem = null }) { Text("取消") }
                        TextButton(onClick = {
                            val raw = renameText.trim()
                            if (raw.isNotEmpty()) {
                                val name = if (raw.contains('.')) raw else raw + fileExt(item.name)
                                com.downloadsource.storage.VaultRepo.rename(ctx, item, name)
                                android.widget.Toast.makeText(ctx, "已重命名", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            renameItem = null
                            vaultTick++
                        }) { Text("保存") }
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
                            val ok = runCatching {
                                com.downloadsource.storage.VaultRepo.import(ctx, file, file.name)
                            }.getOrDefault(false)
                            if (ok) {
                                files = files - file
                                vaultItems = com.downloadsource.storage.VaultRepo.list(ctx)
                                vaultCount = vaultItems.size
                                android.widget.Toast.makeText(ctx, "已加密移入保险箱", android.widget.Toast.LENGTH_SHORT).show()
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
