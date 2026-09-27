package us.wangxy.voicebook.twine

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.theme.LocalTwineTokens

/**
 * 纸面段落卡:Twine 式非线性叙事里"一个 passage"的容器。
 * 纯 foundation 实现 —— 没有 ripple、没有 Material 表面,纸缘描边 + 极浅
 * 投影 + 入场显现动效,一切视觉由 [LocalTwineTokens] 驱动。
 *
 * [revealed] 为 null 时组件自行入场(挂载即淡入);传布尔值则由调用方控制
 * (例如点击"继续"后才显现下一段,是 Twine 叙事的核心交互)。
 */
@Composable
fun TwinePassage(
    modifier: Modifier = Modifier,
    revealed: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalTwineTokens.current
    var mounted by remember { mutableStateOf(revealed != false) }
    if (revealed == null) LaunchedEffect(Unit) { mounted = true }
    val visible = revealed ?: mounted
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(tokens.revealDurationMillis, easing = tokens.revealEasing),
        label = "twinePassageReveal",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(reveal)
            .graphicsLayer { translationY = (1f - reveal) * 24f }
            .shadow(3.dp, RoundedCornerShape(tokens.passageCorner))
            .border(1.dp, tokens.paperEdge, RoundedCornerShape(tokens.passageCorner))
            .background(tokens.paper, RoundedCornerShape(tokens.passageCorner))
            .padding(vertical = 16.dp, horizontal = 18.dp),
        content = content,
    )
}

/**
 * 超文本链接:Twine 段落里的跳转词。墨色强调 + 手写感的动画下划线
 * (按压时下划线收回、墨色淡去)—— 刻意不用 Material 的 ripple。
 */
@Composable
fun TwineLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
) {
    val tokens = LocalTwineTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink by animateColorAsState(
        targetValue = if (pressed) tokens.inkAccentMuted else tokens.inkAccent,
        animationSpec = tween(150),
        label = "twineLinkInk",
    )
    val underline by animateFloatAsState(
        targetValue = if (pressed) 0f else 1f,
        animationSpec = tween(220, easing = tokens.revealEasing),
        label = "twineLinkUnderline",
    )
    Text(
        text = text,
        style = style.copy(color = ink),
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                val y = size.height - stroke
                drawLine(
                    color = ink.copy(alpha = 0.7f),
                    start = Offset(0f, y),
                    end = Offset(size.width * underline, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            .padding(vertical = 2.dp),
    )
}
