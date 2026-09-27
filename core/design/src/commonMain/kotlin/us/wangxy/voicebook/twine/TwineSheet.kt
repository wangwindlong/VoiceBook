package us.wangxy.voicebook.twine

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.DragIndication
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.SheetDetent
import com.composeunstyled.UnstyledModalBottomSheet
import com.composeunstyled.rememberModalBottomSheetState
import us.wangxy.voicebook.theme.LocalTwineTokens
import us.wangxy.voicebook.theme.defaultRevealEasing

/**
 * Twine 风模态底部抽屉:行为交给 compose-unstyled(拖拽、多档位、返回键),
 * 视觉交给令牌 —— 纸面、纸缘、墨色拖拽条。
 *
 * [visible] 控制显隐;用户下拉/点遮罩/返回时回调 [onDismiss](调用方把
 * visible 置回 false)。[peekFraction] 是首档高度占容器比例,拖满可到
 * FullyExpanded。
 */
@Composable
fun TwineSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    peekFraction: Float = 0.55f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalTwineTokens.current
    val peek = remember(peekFraction) {
        SheetDetent("twinePeek") { containerHeight, _ -> containerHeight * peekFraction }
    }
    val state = rememberModalBottomSheetState(
        initialDetent = SheetDetent.Hidden,
        detents = listOf(SheetDetent.Hidden, peek, SheetDetent.FullyExpanded),
    )
    LaunchedEffect(visible) {
        state.targetDetent = if (visible) peek else SheetDetent.Hidden
    }
    // 用户以任何方式收起抽屉(下拉/点遮罩/返回)都归一到 onDismiss
    LaunchedEffect(state.currentDetent) {
        if (visible && state.currentDetent == SheetDetent.Hidden) onDismiss()
    }
    UnstyledModalBottomSheet(
        state = state,
        onDismiss = onDismiss,
        overlay = {
            Scrim(
                scrimColor = tokens.ink.copy(alpha = 0.45f),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            )
        },
    ) {
        Sheet(
            modifier = modifier
                .fillMaxWidth()
                .background(tokens.paper, RoundedCornerShape(topStart = tokens.passageCorner, topEnd = tokens.passageCorner)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 20.dp),
            ) {
                DragIndication(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 6.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(tokens.inkFaded.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                )
                content()
            }
        }
    }
}
