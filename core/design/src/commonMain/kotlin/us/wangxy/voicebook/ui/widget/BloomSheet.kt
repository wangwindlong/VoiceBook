package us.wangxy.voicebook.ui.widget

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
import us.wangxy.voicebook.bloom.LocalBloomTokens
import us.wangxy.voicebook.theme.defaultRevealEasing

/**
 * Bloom 底部抽屉:基于 compose-unstyled 的 modal bottom sheet 原语,
 * 外观(背景色/圆角/拖拽条/边缘色)全部读 LocalBloomTokens + MaterialTheme colorScheme。
 *
 * [visible] 控制显隐;用户下拉/点遮罩/返回时回调 [onDismiss](调用方把
 * visible 置回 false)。[peekFraction] 是首档高度占容器比例,拖满可到
 * FullyExpanded。
 */
@Composable
fun BloomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    peekFraction: Float = 0.55f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalBloomTokens.current
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val peek = remember(peekFraction) {
        SheetDetent("bloomPeek") { containerHeight, _ -> containerHeight * peekFraction }
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
                scrimColor = scheme.scrim.copy(alpha = 0.45f),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            )
        },
    ) {
        Sheet(
            modifier = modifier
                .fillMaxWidth()
                .background(tokens.glow.copy(alpha = 0.08f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
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
                        .background(scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                )
                content()
            }
        }
    }
}