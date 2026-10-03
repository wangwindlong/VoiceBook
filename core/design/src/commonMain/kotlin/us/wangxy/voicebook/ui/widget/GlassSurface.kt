@file:OptIn(ExperimentalHazeApi::class)

package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

val LocalGlassState = compositionLocalOf<HazeState?> { null }

/** Blur only the captured wallpaper, leaving text and controls sharp. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    opacity: Float = 0.76f,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = LocalGlassState.current
    val shape = RoundedCornerShape(20.dp)
    val glassModifier = if (state == null) modifier else modifier.clip(shape).hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.surface,
            tint = HazeTint(tint.copy(alpha = opacity)),
            blurRadius = 24.dp,
            noiseFactor = 0.02f,
            fallbackTint = HazeTint(tint.copy(alpha = (opacity + 0.12f).coerceAtMost(1f))),
        ),
    ) {
        // 模糊输入降到约 1/3 分辨率再放大，视差滑动时每帧重模糊的像素量约降为 1/9
        inputScale = HazeInputScale.Auto
    }
    val color = if (state == null) tint.copy(alpha = opacity) else Color.Transparent
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    if (onClick == null) {
        Surface(modifier = glassModifier, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, border = border) {
            Box(content = content)
        }
    } else {
        Surface(onClick = onClick, modifier = glassModifier, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, border = border) {
            Box(content = content)
        }
    }
}
