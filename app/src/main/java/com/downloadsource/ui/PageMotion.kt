package com.downloadsource.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 每个页面一套独立的三角函数入场。
 * 浏览器页承载 WebView，只能用布局位移，不能加 alpha/scale/rotation 图层，
 * 否则部分机型会把 WebView 渲染成黑屏。
 */
@Composable
fun PageMotion(index: Int, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(index) { anim.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
    val t = anim.value
    val phase = t * PI.toFloat()

    when (index) {
        0 -> {
            val dx = (18f * sin(phase * 2f) * (1f - t)).roundToInt()
            val dy = (52f * (1f - t) + 8f * sin(phase)).roundToInt()
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(dx, dy) }
                    .alpha(0.55f + 0.45f * t)
            ) { content() }
        }
        1 -> {
            val dx = (36f * cos((1f - t) * PI.toFloat() / 2f) * (1f - t)).roundToInt()
            Box(Modifier.fillMaxSize().offset { IntOffset(dx, 0) }) { content() }
        }
        else -> {
            val sc = 0.90f + 0.10f * sin(t * PI.toFloat() / 2f)
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = sc
                    scaleY = sc
                    translationX = -38f * (1f - t)
                    rotationZ = 2.5f * cos(phase)
                    alpha = 0.5f + 0.5f * t
                }
            ) { content() }
        }
    }
}
