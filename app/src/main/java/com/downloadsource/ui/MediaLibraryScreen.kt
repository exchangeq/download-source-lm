package com.downloadsource.ui

import android.content.Intent
import androidx.compose.foundation.background
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
    var vaultPwd by remember { mutableStateOf("") }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    val filters = listOf("全部", "视频", "音频", "安装包")

    LaunchedEffect(selectedFilter, showVault) {
        withContext(Dispatchers.IO) {
            if (!showVault) {
                val dir = DirStore.resolveDownloadDir(ctx)
                files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            } else {
                val vaultDir = File(DirStore.resolveDownloadDir(ctx), ".vault")
                if (!vaultDir.exists()) vaultDir.mkdirs()
                vaultFiles = vaultDir.listFiles()?.filter { it.isFile } ?: emptyList()
            }
        }
    }

    if (showVault) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showVault = false }) { Icon(Icons.Default.ArrowBack, null, tint = palette.onSurface) }
                Text("🔒 私密保险箱", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = palette.onSurface)
            }
            Spacer(Modifier.height(16.dp))
            if (vaultFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("保险箱空空如也 (￣▽￣) (￣▽￣)", color = palette.onSurfaceDim) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vaultFiles) { file ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).clickable { onPlayVideo(file) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (file.name.endsWith(".mp4")) Icons.Default.VideoFile else Icons.Default.InsertDriveFile, null, tint = palette.primary.first())
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, color = palette.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${file.length() / 1024} KB", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { 
                                val dest = File(DirStore.resolveDownloadDir(ctx), file.name)
                                file.renameTo(dest)
                                vaultFiles = vaultFiles - file
                            }) { Icon(Icons.Default.LockOpen, "移出", tint = palette.primary.first()) }
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
            if (ThemeStore.getLockPassword(ctx) != null) {
                IconButton(onClick = { showVaultAuth = true }) { Icon(Icons.Default.Lock, "私密保险箱", tint = palette.primary.first()) }
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
                "视频" -> it.name.endsWith(".mp4", true) || it.name.endsWith(".mkv", true) || it.name.endsWith(".ts", true)
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
                                if (file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true)) {
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
                            val vaultDir = File(DirStore.resolveDownloadDir(ctx), ".vault")
                            if (!vaultDir.exists()) vaultDir.mkdirs()
                            val dest = File(vaultDir, file.name)
                            file.renameTo(dest)
                            files = files - file
                        }) { Icon(Icons.Default.Lock, "移入加密箱", tint = palette.onSurfaceDim) }
                        IconButton(onClick = { if (file.delete()) files = files - file }) { Icon(Icons.Default.Delete, null, tint = palette.onSurfaceDim) }
                    }
                }
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
                        showVault = true; showVaultAuth = false; vaultPwd = ""
                    } else android.widget.Toast.makeText(ctx, "密码不对哦 (￣ε(#￣)", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("解锁") }
            }
        }
    }
}
