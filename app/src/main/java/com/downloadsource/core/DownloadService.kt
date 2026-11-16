package com.downloadsource.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.downloadsource.MainActivity

class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 修复核心：第一时间启动前台，防止 5 秒超时闪退！
        try {
            val channelId = CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(channelId) == null) {
                    mgr.createNotificationChannel(NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW))
                }
            }
            val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Download source 正在运行 (●'◡'●)")
                .setContentText("保障下载不掉线")
                .setContentIntent(openIntent)
                .setOngoing(true).setOnlyAlertOnce(true)
                .build()
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) { }

        // 再去获取唤醒锁
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DownloadSource::WakeLock").apply {
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) { }

        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val NOTIF_ID = 1001
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) { }
        }
        fun stop(context: Context) { 
            try { context.stopService(Intent(context, DownloadService::class.java)) } catch (e: Exception) {}
        }
        
        fun updateProgress(context: Context, fileName: String, downloaded: Long, total: Long, speed: Long) {
            try {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val openIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                val speedText = when {
                    speed >= 1L shl 20 -> String.format("%.1f MB/s", speed / (1.0 * (1L shl 20)))
                    speed >= 1L shl 10 -> String.format("%.1f KB/s", speed / (1.0 * (1L shl 10)))
                    else -> "$speed B/s"
                }
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(fileName)
                    .setContentText("$progress% · $speedText")
                    .setContentIntent(openIntent)
                    .setOngoing(true).setProgress(100, progress, total <= 0).setOnlyAlertOnce(true).build()
                mgr.notify(NOTIF_ID, notif)
            } catch (e: Exception) { }
        }
    }
}
