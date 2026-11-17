package com.downloadsource.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.downloadsource.ThemeStore

data class GradientPalette(val primary: List<Color>, val accent: List<Color>, val background: List<Color>, val surface: Color, val onSurface: Color, val onSurfaceDim: Color)

// 雾霾蓝 / 优雅灰
val LightPalette = GradientPalette(
    primary = listOf(Color(0xFF5B8DEF), Color(0xFF7AA2F7)), 
    accent = listOf(Color(0xFF4ECDC4)),
    background = listOf(Color(0xFFF4F7FB), Color(0xFFE6EBF5)), 
    surface = Color(0xE6FFFFFF), 
    onSurface = Color(0xFF2D3748), 
    onSurfaceDim = Color(0xFF718096)
)
// 赛博暗夜
val DarkPalette = GradientPalette(
    primary = listOf(Color(0xFF66B2FF), Color(0xFF3399FF)), 
    accent = listOf(Color(0xFF00FFCC)),
    background = listOf(Color(0xFF121826), Color(0xFF1A2235)), 
    surface = Color(0x80000000), 
    onSurface = Color(0xFFE2E8F0), 
    onSurfaceDim = Color(0xFFA0AEC0)
)

// 玫瑰金
val RoseLightPalette = LightPalette.copy(primary = listOf(Color(0xFFF08A8A), Color(0xFFFFADAD)), background = listOf(Color(0xFFFCF5F5), Color(0xFFF7E8E8)))
val RoseDarkPalette = DarkPalette.copy(primary = listOf(Color(0xFFFF9A9A), Color(0xFFFF7A7A)), background = listOf(Color(0xFF2D1F1F), Color(0xFF1F1515)))

// 极光绿
val ForestLightPalette = LightPalette.copy(primary = listOf(Color(0xFF48BB78), Color(0xFF68D391)), background = listOf(Color(0xFFF0FDF4), Color(0xFFDCFCE7)))
val ForestDarkPalette = DarkPalette.copy(primary = listOf(Color(0xFF68D391), Color(0xFF48BB78)), background = listOf(Color(0xFF022C22), Color(0xFF064E3B)))

// 日落
val SunsetLightPalette = LightPalette.copy(primary = listOf(Color(0xFFFF8A65), Color(0xFFFFB74D)), background = listOf(Color(0xFFFFF6EF), Color(0xFFFFE6D5)))
val SunsetDarkPalette = DarkPalette.copy(primary = listOf(Color(0xFFFF9E80), Color(0xFFFFCC80)), background = listOf(Color(0xFF2A1B12), Color(0xFF3A2418)))

// 深海
val DeepSeaLightPalette = LightPalette.copy(primary = listOf(Color(0xFF2F6FED), Color(0xFF14B8A6)), background = listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE)))
val DeepSeaDarkPalette = DarkPalette.copy(primary = listOf(Color(0xFF5B9CF8), Color(0xFF2DD4BF)), background = listOf(Color(0xFF081426), Color(0xFF0E2038)))

// 赛博
val CyberLightPalette = LightPalette.copy(primary = listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE)), background = listOf(Color(0xFFF6F2FF), Color(0xFFE8E1FF)))
val CyberDarkPalette = DarkPalette.copy(primary = listOf(Color(0xFFA78BFA), Color(0xFF67E8F9)), background = listOf(Color(0xFF120C24), Color(0xFF1B1035)))

// 毛玻璃（半透明磨砂面板 + 渐变背景）
val GlassLightPalette = LightPalette.copy(
    primary = listOf(Color(0xFF6D8BFF), Color(0xFF9B7BFF)),
    background = listOf(Color(0xFFEAF1FF), Color(0xFFF6ECFF), Color(0xFFE8FFF9)),
    surface = Color(0xB8FFFFFF)
)
val GlassDarkPalette = DarkPalette.copy(
    primary = listOf(Color(0xFF8BA4FF), Color(0xFFB79BFF)),
    background = listOf(Color(0xFF0A1120), Color(0xFF141A31), Color(0xFF0B1B1D)),
    surface = Color(0x9E1B2233)
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun rememberPalette(): GradientPalette {
    val ctx = LocalContext.current
    val isDark = when (ThemeStore.get(ctx)) { ThemeStore.LIGHT -> false; ThemeStore.DARK -> true; else -> isSystemInDarkTheme() }
    return when (ThemeStore.getThemeStyle(ctx)) {
        1 -> if (isDark) RoseDarkPalette else RoseLightPalette
        2 -> if (isDark) ForestDarkPalette else ForestLightPalette
        3 -> if (isDark) SunsetDarkPalette else SunsetLightPalette
        4 -> if (isDark) DeepSeaDarkPalette else DeepSeaLightPalette
        5 -> if (isDark) CyberDarkPalette else CyberLightPalette
        6 -> if (isDark) GlassDarkPalette else GlassLightPalette
        else -> if (isDark) DarkPalette else LightPalette
    }
}

val SafeTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = FontFamily.SansSerif),
    displayMedium = Typography().displayMedium.copy(fontFamily = FontFamily.SansSerif),
    displaySmall = Typography().displaySmall.copy(fontFamily = FontFamily.SansSerif),
    headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.SansSerif),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.SansSerif),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = FontFamily.SansSerif),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif),
    titleMedium = Typography().titleMedium.copy(fontFamily = FontFamily.SansSerif),
    titleSmall = Typography().titleSmall.copy(fontFamily = FontFamily.SansSerif),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    bodySmall = Typography().bodySmall.copy(fontFamily = FontFamily.SansSerif),
    labelLarge = Typography().labelLarge.copy(fontFamily = FontFamily.SansSerif),
    labelMedium = Typography().labelMedium.copy(fontFamily = FontFamily.SansSerif),
    labelSmall = Typography().labelSmall.copy(fontFamily = FontFamily.SansSerif)
)

@Composable
fun GradientBackground(palette: GradientPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(typography = SafeTypography) {
            val transition = rememberInfiniteTransition(label = "aurora")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Reverse),
                label = "phase"
            )
            val glow by transition.animateFloat(
                initialValue = 0.18f,
                targetValue = 0.42f,
                animationSpec = infiniteRepeatable(tween(4800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "glow"
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        colors = palette.background,
                        start = Offset(0f, -900f * phase),
                        end = Offset(1000f * phase + 100f, 1900f)
                    )
                )
            ) {
                // 缓慢流动的极光光斑，给液态玻璃背景一点活气
                Box(
                    Modifier.align(Alignment.TopEnd).size(320.dp)
                        .offset(x = 90.dp * phase, y = (-70).dp * phase)
                        .background(Brush.radialGradient(listOf(palette.primary.first().copy(alpha = glow), Color.Transparent)), CircleShape)
                )
                Box(
                    Modifier.align(Alignment.BottomStart).size(380.dp)
                        .offset(x = (-130).dp * phase, y = 50.dp * phase)
                        .background(Brush.radialGradient(listOf(palette.accent.first().copy(alpha = glow * 0.75f), Color.Transparent)), CircleShape)
                )
                // 注意：这里不能对整棵内容树做 scale/rotation，WebView 被变换层包裹会渲染成黑屏。
                // 入场动画下放到各页面内部（PageMotion），WebView 本身保持零变换。
                content()
            }
        }
    }
}
