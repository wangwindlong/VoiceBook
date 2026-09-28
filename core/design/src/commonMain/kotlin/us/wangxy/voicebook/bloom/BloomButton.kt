package us.wangxy.voicebook.bloom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 实心按钮：四角同一 squircle，按下立刻缩小（短点也会），松手回弹。
 *
 * [style] 是功能语义（普通 / 高亮 / 错误 / 选中）；禁用请传 [enabled] = false。
 * [shadow] 默认关闭，需要时用 [BloomShadow.Soft] 或 [BloomShadow.Deep]。
 * [glow] 为 null 时跟风格走：高亮/错误/选中默认有光晕。
 */
@Composable
fun BloomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BloomButtonStyle = BloomButtonStyle.Normal,
    shadow: BloomShadow = BloomShadow.None,
    glow: Boolean? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalBloomTokens.current
    val scheme = MaterialTheme.colorScheme
    val palette = bloomButtonPalette(style, enabled, scheme)
    val shape = rememberBloomShape(tokens.buttonCorner)
    val fill: Brush = if (palette.container == palette.containerEnd) {
        SolidColor(palette.container)
    } else {
        Brush.linearGradient(listOf(palette.container, palette.containerEnd))
    }
    val useGlow = glow ?: palette.glowDefault
    val useShadow = shadow != BloomShadow.None && enabled
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .bloomHalo(
                color = palette.glow,
                enabled = useGlow && enabled,
            )
            .then(
                if (useShadow) {
                    Modifier.shadow(
                        elevation = shadow.elevation,
                        shape = shape,
                        clip = false,
                        ambientColor = palette.shadow.copy(alpha = 0.32f),
                        spotColor = palette.shadow.copy(alpha = 0.28f),
                    )
                } else {
                    Modifier
                },
            )
            .bloomPress(enabled = enabled)
            .clip(shape)
            .background(fill)
            .then(
                if (palette.borderWidth > 0.dp) {
                    Modifier.border(palette.borderWidth, palette.border, shape)
                } else {
                    Modifier
                },
            )
            .bloomSheen(enabled = palette.sheen && tokens.sheen && enabled)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            CompositionLocalProvider(LocalContentColor provides palette.content) {
                content()
            }
        },
    )
}

@Composable
fun BloomTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BloomButtonStyle = BloomButtonStyle.Normal,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        style == BloomButtonStyle.Error -> scheme.error
        style == BloomButtonStyle.Highlight || style == BloomButtonStyle.Selected -> scheme.primary
        else -> scheme.primary
    }
    Row(
        modifier = modifier
            .bloomPress(enabled = enabled, scale = 0.94f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        },
    )
}

@Composable
fun BloomCard(
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val shape = rememberBloomShape(24.dp)
    Box(
        modifier = modifier
            .bloomHalo(enabled = glow, intensity = LocalBloomTokens.current.glowIntensity * 0.55f)
            .clip(shape)
            .background(containerColor)
            .padding(contentPadding),
    ) {
        content()
    }
}
