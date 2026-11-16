package com.downloadsource.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.downloadsource.core.MirrorResolver

class SourceManageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = rememberPalette()
            GradientBackground(palette) { MaterialTheme { SourceScreen() } }
        }
    }
}

@Composable
fun SourceScreen() {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    var enabledList by remember { mutableStateOf(MirrorResolver.load(ctx)) }
    var inputUrl by remember { mutableStateOf("") }

    val allSources = (MirrorResolver.AVAILABLE_MIRRORS + enabledList).distinct()
    val sortedMirrors = allSources.sortedByDescending { enabledList.contains(it) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("镜像源管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = palette.onSurface)
        Text("勾选启用的镜像源（已勾选优先排列）", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                placeholder = { Text("添加自定义镜像 (http...)") },
                singleLine = true,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val url = inputUrl.trim()
                if (url.isNotBlank() && url.startsWith("http")) {
                    if (!enabledList.contains(url)) {
                        enabledList = enabledList + url
                        MirrorResolver.save(ctx, enabledList)
                        Toast.makeText(ctx, "添加成功，非常完美 (๑•̀ㅂ•́)و✧，非常完美 (๑•̀ㅂ•́)و✧", Toast.LENGTH_SHORT).show()
                        inputUrl = ""
                    } else {
                        Toast.makeText(ctx, "这个源已经在用啦 (￣▽￣)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(ctx, "这好像不是一个合法的链接呢 (・_・;)", Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.Default.Add, null) }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortedMirrors) { mirror ->
                val isChecked = enabledList.contains(mirror)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        // 修复：使用 primary.first().copy
                        .background(if (isChecked) palette.primary.first().copy(alpha = 0.15f) else palette.surface)
                        .clickable {
                            enabledList = if (isChecked) enabledList - mirror else enabledList + mirror
                            MirrorResolver.save(ctx, enabledList)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (mirror.isBlank()) "原生直连 (不加速)" else mirror,
                        color = palette.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal
                    )
                    if (!MirrorResolver.AVAILABLE_MIRRORS.contains(mirror) && mirror.isNotBlank()) {
                        IconButton(onClick = {
                            enabledList = enabledList - mirror
                            MirrorResolver.save(ctx, enabledList)
                        }) { Icon(Icons.Default.Delete, null, tint = palette.onSurfaceDim) }
                    }
                }
            }
        }
    }
}
