package com.polytune.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * MD3E 的材质形变：圆角数字谱系（如 12 边 vs 9 边的饼干形）。
 * Compose 官方 MaterialShapes 只在 1.4 以后稳定，这里自己画一套通用形变，
 * 任何版本都能用，且可用来做 loading 与页面转场。
 */

/** 生成一个 n 边圆角多边形路径，cornerRadius 为 0..1 的圆化比例。 */
fun roundedPolygonPath(
    sides: Int,
    rotationDeg: Float,
    radius: Float,
    roundness: Float,
    center: Offset = Offset(radius, radius),
): Path {
    val path = Path()
    val step = (2 * PI / sides).toFloat()
    val start = rotationDeg * PI.toFloat() / 180f
    val pts = (0 until sides).map { i ->
        val a = start + step * i
        Offset(center.x + radius * cos(a), center.y + radius * sin(a))
    }
    val r = radius * roundness * 0.45f
    for (i in pts.indices) {
        val p = pts[i]
        val prev = pts[(i - 1 + pts.size) % pts.size]
        val next = pts[(i + 1) % pts.size]
        val inDir = Offset(p.x - prev.x, p.y - prev.y).normalized()
        val outDir = Offset(next.x - p.x, next.y - p.y).normalized()
        val a = Offset(p.x - inDir.x * r, p.y - inDir.y * r)
        val b = Offset(p.x + outDir.x * r, p.y + outDir.y * r)
        if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
        path.quadraticTo(p.x, p.y, b.x, b.y)
    }
    path.close()
    return path
}

private fun Offset.normalized(): Offset {
    val len = kotlin.math.sqrt(x * x + y * y).takeIf { it > 0.0001f } ?: 1f
    return Offset(x / len, y / len)
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun lerp(a: Dp, b: Dp, t: Float): Dp = Dp(lerp(a.value, b.value, t.coerceIn(0f, 1f)))

/**
 * 会呼吸的材质形状。用于播放页封面占位、首页 loading、banner 角标。
 * from/to 的边数不同即产生「数字形变」效果，这是 MD3E 的标志性动画。
 */
@Composable
fun MorphingShape(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = Color.Unspecified,
    fromSides: Int = 12,
    toSides: Int = 9,
    strokeWidth: Dp = 0.dp,
    periodMs: Long = 2200,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fromSides, toSides) {
        val spec: FiniteAnimationSpec<Float> = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessVeryLow,
        )
        while (true) {
            progress.animateTo(1f, spec)
            progress.animateTo(0f, spec)
        }
    }
    val sides = lerp(fromSides.toFloat(), toSides.toFloat(), progress.value).toInt().coerceAtLeast(3)
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f * 0.92f
        val path = roundedPolygonPath(
            sides = sides,
            rotationDeg = progress.value * 360f / sides,
            radius = r,
            roundness = lerp(0.9f, 0.5f, progress.value),
            center = Offset(this.size.width / 2f, this.size.height / 2f),
        )
        val c = if (color == Color.Unspecified) Color.White else color
        if (strokeWidth > 0.dp) drawPath(path, c, style = Stroke(strokeWidth.toPx()))
        else drawPath(path, c)
    }
}

/** 三点弹性 loading，用 spring 相位错开的缩放表达 M3E 的节奏感。 */
@Composable
fun ExpressiveLoading(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    dot: Dp = 10.dp,
) {
    val a1 = remember { Animatable(0.4f) }
    val a2 = remember { Animatable(0.4f) }
    val a3 = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        val spec = spring<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)
        kotlinx.coroutines.coroutineScope {
            launch { while (true) { a1.animateTo(1f, spec); a1.animateTo(0.4f, spec) } }
            launch {
                kotlinx.coroutines.delay(120)
                while (true) { a2.animateTo(1f, spec); a2.animateTo(0.4f, spec) }
            }
            launch {
                kotlinx.coroutines.delay(240)
                while (true) { a3.animateTo(1f, spec); a3.animateTo(0.4f, spec) }
            }
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(dot / 2),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        listOf(a1, a2, a3).forEach { a ->
            Canvas(Modifier.size(dot)) {
                drawCircle(
                    color = if (color == Color.Unspecified) Color.White else color,
                    radius = size.minDimension / 2f * a.value,
                )
            }
        }
    }
}
