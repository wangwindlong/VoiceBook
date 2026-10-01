package us.wangxy.voicebook.twine

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.DragIndication
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.SheetDetent
import com.composeunstyled.UnstyledModalBottomSheet
import com.composeunstyled.rememberModalBottomSheetState
import us.wangxy.voicebook.theme.LocalTwineTokens

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
    // 抽屉真正离开过 Hidden 之后，再回到 Hidden 才是用户关掉。
    // 条件组合进树时 detent 仍是 Hidden；若这时就 onDismiss，调用方会立刻把
    // visible 置回 false，表现为点了入口没反应。
    var revealed by remember { mutableStateOf(false) }
    var nudges by remember { mutableIntStateOf(0) }
    val resting = state.bottomSheetState.currentDetent
    SideEffect {
        if (resting != SheetDetent.Hidden) revealed = true
    }
    LaunchedEffect(visible) {
        if (!visible) {
            revealed = false
            nudges = 0
            state.targetDetent = SheetDetent.Hidden
        }
    }
    LaunchedEffect(visible, revealed, nudges, state.modalState.transitionState.targetState) {
        if (visible && !revealed && !state.modalState.transitionState.targetState && nudges < 5) {
            nudges += 1
            state.targetDetent = peek
        }
    }
    LaunchedEffect(visible, revealed, resting) {
        if (visible && revealed && resting == SheetDetent.Hidden) onDismiss()
    }
    UnstyledModalBottomSheet(
        state = state,
        onDismiss = { if (revealed) onDismiss() },
        overlay = {
            Scrim(
                scrimColor = tokens.ink.copy(alpha = 0.45f),
                enter = EnterTransition.None,
                exit = ExitTransition.None,
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
