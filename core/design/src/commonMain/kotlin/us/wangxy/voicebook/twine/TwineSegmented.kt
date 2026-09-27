package us.wangxy.voicebook.twine

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.wangxy.voicebook.theme.LocalTwineTokens
import us.wangxy.voicebook.theme.defaultRevealEasing

/**
 * Twine 风 iOS 式分段选择器:整条圆角胶囊作轨道,选中段是滑动的墨色胶囊高亮。
 * 不占满屏(宽度由调用方约束),点击切换无 ripple;文字颜色随选中态在
 * 纸色/墨色间切换。用于阅读页顶部的 书架/书城 切换。
 */
@Composable
fun TwineSegmented(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTwineTokens.current
    val capsule = RoundedCornerShape(percent = 50)
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .background(tokens.paper.copy(alpha = 0.7f), capsule)
            .border(1.dp, tokens.paperEdge, capsule),
    ) {
        val segmentWidth = maxWidth / labels.size
        val clamped = selectedIndex.coerceIn(0, labels.size - 1)
        val indicatorX by animateDpAsState(
            targetValue = segmentWidth * clamped,
            animationSpec = tween(200, easing = defaultRevealEasing),
            label = "twineSegmentedIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .shadow(2.dp, capsule)
                .background(tokens.ink, capsule),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val interaction = remember(index) { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = label,
                        style = TextStyle(
                            color = if (index == clamped) tokens.paper else tokens.ink,
                            fontSize = 14.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
