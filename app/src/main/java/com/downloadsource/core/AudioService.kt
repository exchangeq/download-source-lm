package com.downloadsource.core

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class AudioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("audio_path")?.let { path ->
            val uri = android.net.Uri.fromFile(java.io.File(path))
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.player?.release(); mediaSession?.release(); mediaSession = null; super.onDestroy()
    }
}
