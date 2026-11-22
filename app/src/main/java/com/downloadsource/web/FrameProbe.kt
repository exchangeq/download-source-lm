package com.downloadsource.web

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View

/**
 * 截一帧 WebView 的真实像素交给 C 层统计亮度，用来判断「页面其实没画出来」。
 * 只做诊断，不参与渲染，失败一律回调 null，不影响主流程。
 */
object FrameProbe {

    fun grab(view: View, activity: Activity?, onResult: (Bitmap?) -> Unit) {
        val act = activity
        // PixelCopy 需要 API 26+，低版本直接跳过诊断（不影响页面渲染）
        if (android.os.Build.VERSION.SDK_INT < 26 || act == null || view.width <= 0 || view.height <= 0) {
            onResult(null)
            return
        }
        try {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val decor = act.window.decorView
            val left = loc[0].coerceAtLeast(0)
            val top = loc[1].coerceAtLeast(0)
            val right = (loc[0] + view.width).coerceAtMost(decor.width).coerceAtLeast(left + 1)
            val bottom = (loc[1] + view.height).coerceAtMost(decor.height).coerceAtLeast(top + 1)
            if (right - left < 40 || bottom - top < 40) {
                onResult(null)
                return
            }
            val bmp = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
            val handler = Handler(Looper.getMainLooper())
            PixelCopy.request(
                act.window,
                Rect(left, top, right, bottom),
                bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) onResult(bmp) else {
                        bmp.recycle()
                        onResult(null)
                    }
                },
                handler
            )
        } catch (e: Throwable) {
            onResult(null)
        }
    }

    /** 抽样统计亮度：最多取 20 万个采样点，足够判断是否整屏全黑 */
    fun lumaOf(bmp: Bitmap): com.downloadsource.core.ZeroCore.FrameStats? {
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val all = IntArray(w * h)
            bmp.getPixels(all, 0, w, 0, 0, w, h)
            val total = all.size
            val step = (total / 200_000).coerceAtLeast(1)
            if (step == 1) {
                com.downloadsource.core.ZeroCore.frameStats(all)
            } else {
                val sample = IntArray(total / step)
                var i = 0
                var k = 0
                while (i < total) {
                    sample[k++] = all[i]
                    i += step
                }
                com.downloadsource.core.ZeroCore.frameStats(sample)
            }
        } catch (e: Throwable) {
            null
        }
    }
}
