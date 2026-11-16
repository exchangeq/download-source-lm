package com.downloadsource

import android.app.Application
import android.content.Intent
import com.downloadsource.core.DownloadManager
import java.io.PrintWriter
import java.io.StringWriter

class DsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val i = Intent(this, CrashActivity::class.java)
                    .putExtra(CrashActivity.EXTRA_STACK, sw.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
