package us.wangxy.voicebook.bloom

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/**
 * 外发光：用多层径向渐变模拟 bloom，不依赖平台 Blur（Android / iOS / Desktop / Web 表现一致）。
 * 光晕会画出组件边界，父布局不要 clip。
 */
@Composable
fun Modifier.bloomHalo(
    color: Color = LocalBloomTokens.current.glow,
    secondary: Color = LocalBloomTokens.current.glowSecondary,
    intensity: Float = LocalBloomTokens.current.glowIntensity,
    spread: Dp = LocalBloomTokens.current.haloSpread,
    pulse: Boolean = LocalBloomTokens.current.haloPulse,
    enabled: Boolean = true,
): Modifier {
    val transition = rememberInfiniteTransition(label = "bloomHalo")
    val animated by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bloomHaloPulse",
    )
    if (!enabled || intensity <= 0f) return this
    val pulseValue = if (pulse) animated else 1f
    return drawBehind {
        val spreadPx = spread.toPx()
        val alpha = intensity * pulseValue
        val radius = (size.maxDimension * 0.62f + spreadPx) * pulseValue
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.42f * alpha), Color.Transparent),
                center = Offset(size.width * 0.32f, size.height * 0.38f),
                radius = radius,
            ),
            radius = radius,
            center = Offset(size.width * 0.32f, size.height * 0.38f),
        )
        val secondaryRadius = radius * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.28f * alpha), Color.Transparent),
                center = Offset(size.width * 0.78f, size.height * 0.7f),
                radius = secondaryRadius,
            ),
            radius = secondaryRadius,
            center = Offset(size.width * 0.78f, size.height * 0.7f),
        )
    }
}

/**
 * 按下立刻缩小，松手回弹。
 *
 * 不走 `clickable` 的 Press（在可滚动容器里会被延迟，看起来像长按才缩放），
 * 改在 [PointerEventPass.Initial] 里读按下，短点也会压下去。
 */
@Composable
fun Modifier.bloomPress(
    enabled: Boolean = true,
    scale: Float = LocalBloomTokens.current.pressScale,
    minPressedMillis: Int = 80,
): Modifier {
    var pointerDown by remember { mutableStateOf(false) }
    var visualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            pointerDown = false
            visualPressed = false
        }
    }
    LaunchedEffect(pointerDown, enabled) {
        if (!enabled) return@LaunchedEffect
        if (pointerDown) {
            visualPressed = true
        } else {
            delay(minPressedMillis.toLong())
            if (!pointerDown) visualPressed = false
        }
    }
    val animated by animateFloatAsState(
        targetValue = if (visualPressed) scale else 1f,
        animationSpec = if (visualPressed) {
            tween(durationMillis = 55, easing = FastOutLinearInEasing)
        } else {
            spring(dampingRatio = 0.48f, stiffness = 720f)
        },
        label = "bloomPress",
    )
    return this
        .graphicsLayer {
            scaleX = animated
            scaleY = animated
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    pointerDown = event.changes.any { it.pressed }
                }
            }
        }
}

/** 斜向高光扫过表面，让实心色块不那么死。 */
@Composable
fun Modifier.bloomSheen(
    enabled: Boolean = LocalBloomTokens.current.sheen,
    highlight: Color = Color.White,
): Modifier {
    val transition = rememberInfiniteTransition(label = "bloomSheen")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bloomSheenShift",
    )
    if (!enabled) return this
    return drawWithContent {
        drawContent()
        val startX = size.width * (shift * 1.6f - 0.4f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    highlight.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
                start = Offset(startX, 0f),
                end = Offset(startX + size.width * 0.35f, size.height),
            ),
        )
    }
}
