package com.downloadsource.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

private fun formatClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

@Composable
fun VideoPlayerScreen(uri: Uri, key: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val prefs = remember { ctx.getSharedPreferences("lm_player", Context.MODE_PRIVATE) }
    val posKey = "pos_" + key.ifBlank { uri.toString() }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var loop by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }
    var restored by remember { mutableStateOf(false) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) subtitleUri = picked
    }

    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    val playerView = remember {
        PlayerView(ctx).apply {
            this.player = player
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    // 字幕挂载：换了字幕轨不打断播放进度
    LaunchedEffect(subtitleUri) {
        val sub = subtitleUri ?: return@LaunchedEffect
        val at = player.currentPosition
        val mime = if (uri.toString().lowercase().endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        runCatching {
            player.setMediaItem(
                MediaItem.Builder().setUri(uri)
                    .setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(sub)
                                .setMimeType(mime).setLanguage("zh").setSelectionFlags(0).build()
                        )
                    ).build(),
                at
            )
            player.prepare()
            player.playWhenReady = isPlaying
        }
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(resizeMode) { playerView.resizeMode = resizeMode }

    LaunchedEffect(uri) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (!restored && duration > 0) {
                val saved = prefs.getLong(posKey, 0L)
                if (saved > 3000L && saved < duration - 3000L) {
                    player.seekTo(saved)
                    toast = "已从上次位置继续播放"
                }
                restored = true
            }
            delay(400)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(1800); toast = "" } }
    LaunchedEffect(hint) { if (hint.isNotBlank()) { delay(1200); hint = "" } }

    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brightness by remember {
        val cur = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (cur < 0f) 0.5f else cur)
    }
    var volume by remember {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { prefs.edit().putLong(posKey, player.currentPosition).apply() }
            runCatching { player.release() }
            runCatching { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            runCatching {
                activity?.window?.let { w ->
                    w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        // 手势：单击显隐控制条 / 双击左右 10 秒 / 横向拖动进度 / 纵向左亮度右音量 / 长按 2 倍速
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    var mode = 0
                    var accX = 0f
                    var accY = 0f
                    var startPos = 0L
                    var baseBrightness = 0.5f
                    var baseVolume = 0.5f
                    detectDragGestures(
                        onDragStart = {
                            startPos = player.currentPosition
                            accX = 0f; accY = 0f
                            baseBrightness = brightness
                            baseVolume = volume
                            mode = 0
                        },
                        onDrag = { change, drag ->
                            accX += drag.x; accY += drag.y
                            if (mode == 0) {
                                mode = if (abs(accX) > abs(accY)) 1 else if (change.position.x < size.width / 2) 2 else 3
                            }
                            when (mode) {
                                1 -> {
                                    val total = duration.coerceAtLeast(1)
                                    val target = (startPos + (accX / size.width * total.toFloat()).toLong() * 2)
                                        .coerceIn(0L, total)
                                    seekPreview = target
                                    hint = "${formatClock(target)} / ${formatClock(total)}"
                                }
                                2 -> {
                                    brightness = (baseBrightness - accY / size.height).coerceIn(0.05f, 1f)
                                    runCatching {
                                        activity?.window?.let { w ->
                                            w.attributes = w.attributes.apply { screenBrightness = brightness }
                                        }
                                    }
                                    hint = "亮度 ${(brightness * 100).toInt()}%"
                                }
                                else -> {
                                    volume = (baseVolume - accY / size.height).coerceIn(0f, 1f)
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).toInt(), 0) }
                                    hint = "音量 ${(volume * 100).toInt()}%"
                                }
                            }
                        },
                        onDragEnd = {
                            if (mode == 1) seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                            mode = 0
                        },
                        onDragCancel = { seekPreview = null; mode = 0 }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) {
                                player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                                hint = "快退 10 秒"
                            } else {
                                player.seekTo((player.currentPosition + 10000).coerceAtMost(duration.coerceAtLeast(1)))
                                hint = "快进 10 秒"
                            }
                        },
                        onLongPress = {
                            speed = if (speed >= 2f) 1f else 2f
                            hint = if (speed >= 2f) "2 倍速播放中" else "恢复 1 倍速"
                        }
                    )
                }
        )

        if (hint.isNotBlank()) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 88.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(hint, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
        if (toast.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(toast, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }

        if (buffering) {
            CircularProgressIndicator(
                color = Color.White, strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(42.dp)
            )
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                    Text(
                        uri.lastPathSegment ?: "视频",
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSpeedMenu = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, "倍速", tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("${speed}x", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { s ->
                            DropdownMenuItem(text = { Text("${s}x") }, onClick = { speed = s; showSpeedMenu = false })
                        }
                    }
                    IconButton(onClick = { showAspectMenu = true }) {
                        Icon(Icons.Default.Layers, "画面比例", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showAspectMenu, onDismissRequest = { showAspectMenu = false }) {
                        listOf(
                            "适应屏幕" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                            "铺满裁切" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                            "拉伸填满" to AspectRatioFrameLayout.RESIZE_MODE_FILL
                        ).forEach { pair ->
                            DropdownMenuItem(text = { Text(pair.first) }, onClick = { resizeMode = pair.second; showAspectMenu = false })
                        }
                    }
                    IconButton(onClick = { loop = !loop }) {
                        Icon(
                            Icons.Default.Refresh, "循环",
                            tint = if (loop) Color(0xFF5AC8FA) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Default.Star, "外挂字幕",
                            tint = if (subtitleUri != null) Color(0xFF5AC8FA) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = {
                        runCatching {
                            activity?.requestedOrientation =
                                if (activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE)
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }) { Icon(Icons.Default.Refresh, "旋转", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }

                Spacer(Modifier.weight(1f))

                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(seekPreview ?: position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = (seekPreview ?: position).toFloat(),
                            onValueChange = { seekPreview = it.toLong() },
                            onValueChangeFinished = {
                                seekPreview?.let { player.seekTo(it) }
                                seekPreview = null
                            },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF5AC8FA),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            )
                        )
                        Text(formatClock(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }) {
                            Text("-10 秒", color = Color.White)
                        }
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (isPlaying) "暂停" else "播放", tint = Color.White
                                )
                            }
                        }
                        TextButton(onClick = { player.seekTo((player.currentPosition + 10000).coerceAtMost(duration.coerceAtLeast(1))) }) {
                            Text("+10 秒", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 音频文件复用同一播放器 */
@Composable
fun AudioPlayerScreen(file: File, onBack: () -> Unit) {
    VideoPlayerScreen(Uri.fromFile(file), key = "audio_" + file.name, onBack = onBack)
}
