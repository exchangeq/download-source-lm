package com.downloadsource.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.downloadsource.MainActivity
import com.downloadsource.R

class DownloadWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            val tasks = DownloadManager.tasks.value
            val running = tasks.filter { it.status == TaskStatus.Running }
            val speed = running.sumOf { it.speed }
            val totalProgress = if (running.isEmpty()) 0 else running.sumOf { it.fraction.toInt() } / running.size
            
            views.setTextViewText(R.id.widget_speed, String.format("%.1f KB/s", speed / 1024f))
            views.setProgressBar(R.id.widget_progress, 100, totalProgress, false)
            
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_title, pi)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
