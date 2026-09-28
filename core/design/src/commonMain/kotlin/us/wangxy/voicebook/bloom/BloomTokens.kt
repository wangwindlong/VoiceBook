package us.wangxy.voicebook.bloom

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.theme.AppSkin

/**
 * Bloom 组件层的设计令牌，与 Material ColorScheme / TwineTokens 并行：
 * Twine 负责纸页与墨色，Bloom 负责 chrome（按钮、芯片、卡片）的曲率、光晕和按压弹性。
 */
data class BloomTokens(
    val smoothing: Float,
    val glow: Color,
    val glowSecondary: Color,
    val glowIntensity: Float,
    val haloSpread: Dp,
    val pressScale: Float,
    /** 实心按钮四角同一半径，不跟皮肤的非对称 Shapes 走。 */
    val buttonCorner: Dp,
    val haloPulse: Boolean,
    val sheen: Boolean,
) {
    companion object {
        fun fromScheme(scheme: ColorScheme, skin: AppSkin, dark: Boolean, smoothing: Float): BloomTokens {
            val primary = scheme.primary
            val secondary = scheme.secondary
            return when (skin) {
                AppSkin.Neon -> BloomTokens(
                    smoothing = smoothing,
                    glow = primary,
                    glowSecondary = secondary,
                    glowIntensity = if (dark) 0.88f else 0.48f,
                    haloSpread = 20.dp,
                    pressScale = 0.92f,
                    buttonCorner = 18.dp,
                    haloPulse = true,
                    sheen = true,
                )
                AppSkin.Tide -> BloomTokens(
                    smoothing = smoothing,
                    glow = primary,
                    glowSecondary = secondary,
                    glowIntensity = if (dark) 0.62f else 0.42f,
                    haloSpread = 16.dp,
                    pressScale = 0.92f,
                    buttonCorner = 20.dp,
                    haloPulse = true,
                    sheen = true,
                )
                AppSkin.Fold -> BloomTokens(
                    smoothing = smoothing,
                    glow = primary,
                    glowSecondary = primary,
                    glowIntensity = 0.28f,
                    haloSpread = 10.dp,
                    pressScale = 0.93f,
                    buttonCorner = 16.dp,
                    haloPulse = false,
                    sheen = false,
                )
                AppSkin.System, AppSkin.Dynamic -> BloomTokens(
                    smoothing = smoothing,
                    glow = primary,
                    glowSecondary = secondary,
                    glowIntensity = if (dark) 0.5f else 0.32f,
                    haloSpread = 14.dp,
                    pressScale = 0.92f,
                    buttonCorner = 18.dp,
                    haloPulse = true,
                    sheen = true,
                )
            }
        }
    }
}

val LocalBloomTokens = staticCompositionLocalOf<BloomTokens> {
    error("BloomTokens is missing — ensure VoiceBookTheme wraps the content")
}
