package com.downloadsource.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.downloadsource.core.DownloadTask
import com.downloadsource.core.DownloadManager
import com.downloadsource.core.TaskStatus
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(task.fileName) }

    val animatedProgress by animateFloatAsState(targetValue = task.fraction, animationSpec = tween(400), label = "p")
    val animatedSpeed by animateFloatAsState(targetValue = task.speed.toFloat(), animationSpec = tween(500), label = "speed")

    Box {
      Column {
        Row(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.surface)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), palette.onSurface.copy(alpha = 0.12f))), RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClick = {
                        // 点击展开进度详情；已完成则直接打开文件
                        if (task.status == TaskStatus.Completed) onOpen() else expanded = !expanded
                    },
                    onLongClick = { showSheet = true }
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(animatedProgress, task.status == TaskStatus.Running, task.status == TaskStatus.Completed, palette.primary)
            Spacer(Modifier.width(16.dp))
            
            Column(Modifier.weight(1f)) {
                Text(task.fileName, color = palette.onSurface, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                
                val speedStr = if (animatedSpeed > 0) String.format("%.1f KB/s", animatedSpeed / 1024) else ""
                val displayError = when {
                    task.error == null -> ""
                    task.error!!.contains("canceled", true) || task.error!!.contains("cancelled", true) -> "任务已取消 (￣▽￣) (￣▽￣)"
                    else -> task.error!!
                }
                
                Text(
                    when (task.status) {
                        TaskStatus.Failed -> "失败了: $displayError (´･_･`)"
                        TaskStatus.Completed -> "下载完成啦 (●'◡'●)"
                        TaskStatus.Paused -> "已暂停 (￣▽￣) · ${task.sizeText}"
                        else -> "${(task.fraction * 100).toInt()}% · ${task.sizeText} ${if (speedStr.isNotEmpty()) "· $speedStr" else ""}"
                    },
                    color = if (task.status == TaskStatus.Failed) Color.Red else palette.onSurfaceDim,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            // UI 重构：按钮间距拉大，尺寸微调
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.status != TaskStatus.Completed) {
                    IconButton(
                        onClick = { if (task.status == TaskStatus.Running) onPause() else onResume() },
                        modifier = Modifier.size(36.dp).background(palette.onSurface.copy(alpha = 0.05f), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(if (task.status == TaskStatus.Running) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = palette.primary.first(), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp).background(palette.onSurface.copy(alpha = 0.05f), androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface)
                    .padding(14.dp)
            ) {
                if (task.segments.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        task.segments.take(32).forEach { f ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                                    .background(palette.onSurface.copy(alpha = 0.12f))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight()
                                        .background(palette.primary.first())
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    buildString {
                        task.url.let { u -> runCatching { java.net.URI(u).host }.getOrNull()?.let { append("$it · ") } }
                        append("线程 ${task.threads}")
                        append(" · ${task.sizeText}")
                        if (task.speedText.isNotEmpty()) append(" · ${task.speedText}")
                        if (task.etaText.isNotEmpty()) append(" · 剩余 ${task.etaText}")
                    },
                    color = palette.onSurfaceDim, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                SpeedSparkline(task.speed, palette.primary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("URL", task.url))
                        Toast.makeText(ctx, "链接已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制链接", style = MaterialTheme.typography.bodySmall) }
                    if (task.status == TaskStatus.Failed) {
                        TextButton(onClick = { DownloadManager.start(ctx, task.id) }) { Text("重试", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = onDelete) { Text("删除", color = Color.Red, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = palette.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text("任务操作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = palette.onSurface, modifier = Modifier.padding(bottom = 12.dp))
                    SheetItem("其它应用打开", Icons.Default.OpenInNew, palette.onSurface) {
                        val file = File(task.outputPath ?: "")
                        if (file.exists()) {
                            val uri = try { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file) } catch (e: Exception) { Toast.makeText(ctx, "文件路径好像不太对 (´･_･`)", Toast.LENGTH_SHORT).show(); return@SheetItem }
                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
                            try { ctx.startActivity(Intent.createChooser(intent, "打开文件")) } catch (_: Exception) { }
                        } else Toast.makeText(ctx, "咦？文件不见了 (´･_･`)", Toast.LENGTH_SHORT).show()
                        showSheet = false
                    }
                    SheetItem("重命名", Icons.Default.Edit, palette.onSurface) { renameText = task.fileName; showRenameDialog = true; showSheet = false }
                    SheetItem("复制下载链接", Icons.Default.ContentCopy, palette.onSurface) {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", task.url))
                        Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show(); showSheet = false
                    }
                    SheetItem("删除", Icons.Default.Delete, Color.Red) { onDelete(); showSheet = false }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("重命名") },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { if (renameText.isNotBlank()) DownloadManager.rename(ctx, task.id, renameText); showRenameDialog = false }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
            )
        }
      }
    }
}

@Composable
fun SheetItem(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 14.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(16.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProgressRing(progress: Float, running: Boolean, completed: Boolean, colors: List<Color>) {
    val transition = rememberInfiniteTransition(label = "ring")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    Canvas(Modifier.size(48.dp)) {
        val stroke = 5.dp.toPx(); val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (completed) drawArc(Brush.sweepGradient(colors), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        else if (progress > 0.01f) drawArc(Brush.sweepGradient(colors), if (running) rotation else -90f, (progress * 360f).coerceAtLeast(4f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        else if (running) drawArc(colors.first(), rotation, 90f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 最近 40 个采样点的速度折线 */
@Composable
private fun SpeedSparkline(speed: Long, colors: List<Color>) {
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(speed) {
        samples.add(speed.toFloat())
        if (samples.size > 40) samples.removeAt(0)
    }
    val maxV = (samples.maxOrNull() ?: 1f).coerceAtLeast(1024f)
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        if (samples.size < 2) return@Canvas
        val path = Path()
        val stepX = size.width / (samples.size - 1)
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / maxV) * size.height * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.first(), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
