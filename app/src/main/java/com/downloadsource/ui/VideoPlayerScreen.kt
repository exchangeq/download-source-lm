package com.downloadsource.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
fun VideoPlayerScreen(uri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var speed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(speed) { exoPlayer.setPlaybackSpeed(speed) }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { player = exoPlayer; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 16.dp).background(Color.Black.copy(alpha = 0.5f))
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }

        Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 16.dp)) {
            IconButton(onClick = { showSpeedMenu = true }, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, "Speed", tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("${speed}x", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { s ->
                    DropdownMenuItem(text = { Text("${s}x") }, onClick = { speed = s; showSpeedMenu = false })
                }
            }
        }
    }
}
