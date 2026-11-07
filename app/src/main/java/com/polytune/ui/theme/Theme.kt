package com.polytune.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------------- MD3E 设计令牌 ---------------- */

/**
 * Material 3 Expressive 的核心差异：超大圆角、强调级字阶、统一 spring 动效。
 * 这里用 CompositionLocal 把动效规格暴露出去，避免每处动画硬编码时长。
 */
data class PolyMotion(
    val expressive: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
    val soft: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
    val snappy: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
    val standardEasing: androidx.compose.animation.core.Easing = FastOutSlowInEasing,
    val emphasizedEasing: androidx.compose.animation.core.Easing =
        CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

val LocalPolyMotion = staticCompositionLocalOf { PolyMotion() }

/** M3E 形状：额外提供 morph 用的超大圆角档。 */
val PolyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** 强调字阶：display 层字重更重、字距更紧，这是 expressive 风格的视觉支柱。 */
val PolyTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 54.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp,
    ),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

/** 无动态取色时的兜底配色：以紫蓝为主色的 expressive palette。 */
private fun fallbackDark(): ColorScheme = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    onPrimary = Color(0xFF2B1F5E),
    primaryContainer = Color(0xFF42357D),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFCBC2DC),
    tertiary = Color(0xFF8FD6C4),
    surface = Color(0xFF131218),
    surfaceVariant = Color(0xFF47454F),
    surfaceContainer = Color(0xFF1C1B21),
    surfaceContainerHigh = Color(0xFF242329),
    surfaceContainerHighest = Color(0xFF2C2B32),
    background = Color(0xFF131218),
    onSurface = Color(0xFFE6E1E9),
    onSurfaceVariant = Color(0xFFC9C5D0),
)

private fun fallbackLight(): ColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF0F6B5C),
    surface = Color(0xFFFDF7FF),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    background = Color(0xFFFDF7FF),
)

/** 把官方 M3E 动效规格转成本地 PolyMotion，界面里继续用 LocalPolyMotion 取值。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun motionFromScheme(scheme: MotionScheme): PolyMotion = PolyMotion(
    expressive = scheme.slowSpatialSpec(),
    soft = scheme.defaultSpatialSpec(),
    snappy = scheme.fastSpatialSpec(),
)

/**
 * 主题入口。Android 12+ 走动态取色，以下走内置 expressive 配色，保证视觉一致。
 * 这里用 MaterialExpressiveTheme，它带 Expressive 的 shapes 与 motionScheme 兜底。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PolyTuneTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> fallbackDark()
        else -> fallbackLight()
    }
    val motionScheme = MotionScheme.expressive()
    CompositionLocalProvider(LocalPolyMotion provides motionFromScheme(motionScheme)) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            shapes = PolyShapes,
            typography = PolyTypography,
            motionScheme = motionScheme,
            content = { content() },
        )
    }
}
