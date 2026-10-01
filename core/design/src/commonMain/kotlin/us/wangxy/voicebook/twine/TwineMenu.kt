package us.wangxy.voicebook.twine

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.AnchorSide
import com.composeunstyled.DropdownMenuPanel
import com.composeunstyled.DropdownMenuPanelScope
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledDropdownMenuItem
import com.composeunstyled.UnstyledHorizontalSeparator
import us.wangxy.voicebook.theme.LocalTwineTokens

private val MenuExitEasing = Easing { fraction ->
    1f - LinearOutSlowInEasing.transform(1f - fraction)
}

/**
 * Twine 风下拉菜单:行为交给 compose-unstyled(定位/键盘导航/焦点),
 * 视觉全部来自令牌 —— 纸面浮层、纸缘描边、墨色菜单项。
 *
 * [panel] 里用 [TwineMenuItem] 与 [TwineMenuSeparator] 组织菜单内容;
 * [anchor] 是触发器插槽(自行处理点击展开)。
 */
@Composable
fun TwineMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    anchor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    side: AnchorSide = AnchorSide.Bottom,
    alignment: AnchorAlignment = AnchorAlignment.End,
    sideOffset: Dp = 6.dp,
    panel: @Composable DropdownMenuPanelScope.() -> Unit,
) {
    val tokens = LocalTwineTokens.current
    val shape = RoundedCornerShape(tokens.passageCorner)
    UnstyledDropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        side = side,
        alignment = alignment,
        sideOffset = sideOffset,
        panel = {
            DropdownMenuPanel(
                modifier = Modifier
                    .widthIn(min = 180.dp)
                    .shadow(6.dp, shape)
                    .background(tokens.paper, shape)
                    .border(1.dp, tokens.paperEdge, shape)
                    .padding(vertical = 6.dp),
                enter = scaleIn(
                    animationSpec = tween(140, easing = LinearOutSlowInEasing),
                    initialScale = 0.94f,
                ),
                exit = scaleOut(
                    animationSpec = tween(140, easing = MenuExitEasing),
                    targetScale = 0.94f,
                ),
                content = panel,
            )
        },
        anchor = anchor,
    )
}

/**
 * 菜单项:墨色文字,按压时荧光批注高亮;无 ripple,禁用时落淡墨。
 * 必须在 [TwineMenu] 的 panel 作用域内调用(保持键盘导航顺序)。
 */
@Composable
fun DropdownMenuPanelScope.TwineMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    closeOnClick: Boolean = true,
) {
    val tokens = LocalTwineTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    UnstyledDropdownMenuItem(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (pressed && enabled) tokens.highlight else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        enabled = enabled,
        closeOnClick = closeOnClick,
        interactionSource = interaction,
        indication = null,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (enabled) tokens.ink else tokens.inkFaded,
                fontSize = 14.sp,
            ),
        )
    }
}

/** 菜单分隔线:一条纸缘色的细线。 */
@Composable
fun DropdownMenuPanelScope.TwineMenuSeparator(modifier: Modifier = Modifier) {
    val tokens = LocalTwineTokens.current
    UnstyledHorizontalSeparator(
        color = tokens.paperEdge,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
