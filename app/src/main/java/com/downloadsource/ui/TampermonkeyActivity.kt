package com.downloadsource.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.downloadsource.utils.TampermonkeyManager
import com.downloadsource.utils.UserScript
import java.util.UUID

class TampermonkeyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = rememberPalette()
            GradientBackground(palette) { MaterialTheme { TampermonkeyScreen(onClose = { finish() }) } }
        }
    }
}

@Composable
fun TampermonkeyScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    var scripts by remember { mutableStateOf(TampermonkeyManager.getScripts(ctx)) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, null, tint = palette.onSurface) }
            Text("油猴脚本管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, "添加", tint = palette.primary.first()) }
        }
        
        Spacer(Modifier.height(16.dp))
        
        if (scripts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有脚本哦，点右上角 + 添加一个吧 (๑•̀ㅂ•́)و✧", color = palette.onSurfaceDim)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(scripts) { script ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(script.name, color = palette.onSurface, fontWeight = FontWeight.Bold)
                            Text("长度: ${script.code.length} 字符", color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = script.enabled,
                            onCheckedChange = { isChecked ->
                                scripts = scripts.map { if (it.id == script.id) it.copy(enabled = isChecked) else it }
                                TampermonkeyManager.saveScripts(ctx, scripts)
                            }
                        )
                        IconButton(onClick = {
                            scripts = scripts.filterNot { it.id == script.id }
                            TampermonkeyManager.saveScripts(ctx, scripts)
                        }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加脚本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("脚本名称") }, singleLine = true)
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("JS 代码") }, minLines = 5)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && code.isNotBlank()) {
                        val newScript = UserScript(UUID.randomUUID().toString(), name, code, true)
                        scripts = scripts + newScript
                        TampermonkeyManager.saveScripts(ctx, scripts)
                        showAddDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }
}
