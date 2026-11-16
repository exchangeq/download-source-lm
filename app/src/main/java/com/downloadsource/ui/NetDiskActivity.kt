package com.downloadsource.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.downloadsource.core.DiskParser
import com.downloadsource.core.DownloadManager
import com.downloadsource.core.NetDiskParser
import com.downloadsource.storage.DirStore
import kotlinx.coroutines.launch

class NetDiskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = rememberPalette()
            GradientBackground(palette) {
                MaterialTheme { NetDiskScreen(onBack = { finish() }) }
            }
        }
    }
}

@Composable
fun NetDiskScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    
    var rawText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var parsedFiles by remember { mutableStateOf<List<DiskParser.FileInfo>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = palette.onSurface) }
            Text("网盘解析中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.onSurface)
        }
        
        Text("粘贴分享文本（支持夸克/123/蓝奏云）", color = palette.onSurfaceDim, modifier = Modifier.padding(vertical = 12.dp))
        
        OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            label = { Text("分享链接") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        
        Spacer(Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (rawText.isBlank()) return@Button
                isLoading = true; errorMsg = null; parsedFiles = emptyList(); selectedFiles = emptySet()
                scope.launch {
                    try {
                        val share = NetDiskParser.detect(rawText)
                        if (share.type == NetDiskParser.DiskType.UNKNOWN) {
                            errorMsg = "无法识别该网盘"
                        } else {
                            parsedFiles = DiskParser.parse(ctx, share)
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally { isLoading = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            else Text("一键解析")
        }

        if (errorMsg != null) {
            Text(errorMsg!!, color = Color.Red, modifier = Modifier.padding(top = 16.dp))
        }

        if (parsedFiles.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${parsedFiles.size} 个文件", fontWeight = FontWeight.Bold, color = palette.onSurface)
                TextButton(onClick = {
                    if (selectedFiles.size == parsedFiles.size) selectedFiles = emptySet()
                    else selectedFiles = parsedFiles.map { it.name }.toSet()
                }) {
                    Text(if (selectedFiles.size == parsedFiles.size) "取消全选" else "全选")
                }
            }
            
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(parsedFiles) { file ->
                    val isSelected = selectedFiles.contains(file.name)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            // 修复：使用 primary.first().copy
                            .background(if (isSelected) palette.primary.first().copy(alpha = 0.15f) else palette.surface)
                            .clickable { selectedFiles = if (isSelected) selectedFiles - file.name else selectedFiles + file.name }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.InsertDriveFile, null, tint = if (isSelected) palette.primary.first() else palette.onSurfaceDim)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = palette.onSurface, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text("${file.size / 1024} KB", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            
            Button(
                onClick = {
                    val toDownload = parsedFiles.filter { selectedFiles.contains(it.name) }
                    toDownload.forEach { file ->
                        DownloadManager.add(ctx, file.url, file.name, 8, 0, emptyMap(), DirStore.resolveDownloadDir(ctx))
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = selectedFiles.isNotEmpty()
            ) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("下载选中 (${selectedFiles.size})")
            }
        }
    }
}
