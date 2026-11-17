package com.downloadsource.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 每个页面一套入场曲线（全部由三角函数驱动，互不重复）：
 * 0 下载页：正弦上浮 + 轻微右移
 * 1 浏览器：纯透明度呼吸（WebView 绝不做位移/缩放，否则黑屏）
 * 2 文件库：余弦缩放 + 左移
 * 3 设置页：双频正弦抖动收尾
 */
@Composable
fun PageMotion(index: Int, content: @Composable () -> Unit) {
    if (index == 1) { Box(Modifier.fillMaxSize()) { content() }; return }

    val anim = remember { Animatable(0f) }
    LaunchedEffect(index) { anim.animateTo(1f, tween(720, easing = FastOutSlowInEasing)) }
    val t = anim.value
    val phase = t * PI.toFloat()

    val graph: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        alpha = 0.5f + 0.5f * t
        when (index) {
            0 -> {
                translationY = 46f * (1f - t) + 6f * sin(phase)
                translationX = 22f * (1f - t)
            }
            2 -> {
                val sc = 0.9f + 0.1f * cos((1f - t) * PI.toFloat() / 2f)
                scaleX = sc
                scaleY = sc
                translationX = -34f * (1f - t)
            }
            else -> {
                translationY = 24f * (1f - t)
                rotationZ = 3.5f * sin(phase * 2f)
            }
        }
    }

    Box(Modifier.fillMaxSize().graphicsLayer(graph)) { content() }
}
