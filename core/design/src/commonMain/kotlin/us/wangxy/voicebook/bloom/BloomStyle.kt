package us.wangxy.voicebook.bloom

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 实心按钮的功能语义。禁用不是独立风格，用 [BloomButton] 的 `enabled = false`。 */
enum class BloomButtonStyle {
    /** 次要操作：浅底、细边。 */
    Normal,
    /** 主操作：高亮渐变。 */
    Highlight,
    /** 破坏性操作或校验失败。 */
    Error,
    /** 当前选中。 */
    Selected,
}

/** 按钮周边投影；默认关闭，按场景打开。 */
enum class BloomShadow(val elevation: Dp) {
    None(0.dp),
    Soft(6.dp),
    Deep(14.dp),
}

internal data class BloomButtonPalette(
    val container: Color,
    val containerEnd: Color,
    val content: Color,
    val glow: Color,
    val shadow: Color,
    val border: Color,
    val borderWidth: Dp,
    val glowDefault: Boolean,
    val sheen: Boolean,
)

internal fun bloomButtonPalette(
    style: BloomButtonStyle,
    enabled: Boolean,
    scheme: ColorScheme,
): BloomButtonPalette {
    if (!enabled) {
        return BloomButtonPalette(
            container = scheme.surfaceVariant.copy(alpha = 0.72f),
            containerEnd = scheme.surfaceVariant.copy(alpha = 0.72f),
            content = scheme.onSurface.copy(alpha = 0.38f),
            glow = scheme.onSurface,
            shadow = scheme.onSurface,
            border = scheme.outline.copy(alpha = 0.22f),
            borderWidth = 1.dp,
            glowDefault = false,
            sheen = false,
        )
    }
    return when (style) {
        BloomButtonStyle.Normal -> BloomButtonPalette(
            container = scheme.secondaryContainer,
            containerEnd = scheme.secondaryContainer,
            content = scheme.onSecondaryContainer,
            glow = scheme.secondary,
            shadow = scheme.secondary,
            border = scheme.outline.copy(alpha = 0.28f),
            borderWidth = 1.dp,
            glowDefault = false,
            sheen = false,
        )
        BloomButtonStyle.Highlight -> BloomButtonPalette(
            container = scheme.primary,
            containerEnd = scheme.secondary,
            content = scheme.onPrimary,
            glow = scheme.primary,
            shadow = scheme.primary,
            border = Color.Transparent,
            borderWidth = 0.dp,
            glowDefault = true,
            sheen = true,
        )
        BloomButtonStyle.Error -> BloomButtonPalette(
            container = scheme.error,
            containerEnd = scheme.error,
            content = scheme.onError,
            glow = scheme.error,
            shadow = scheme.error,
            border = Color.Transparent,
            borderWidth = 0.dp,
            glowDefault = true,
            sheen = false,
        )
        BloomButtonStyle.Selected -> BloomButtonPalette(
            container = scheme.primary,
            containerEnd = scheme.primaryContainer,
            content = scheme.onPrimary,
            glow = scheme.primary,
            shadow = scheme.primary,
            border = scheme.onPrimary.copy(alpha = 0.35f),
            borderWidth = 2.dp,
            glowDefault = true,
            sheen = true,
        )
    }
}
