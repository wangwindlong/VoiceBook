package us.wangxy.voicebook.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp

/**
 * Bloom 对话框:使用基础 Compose 原语实现(避免 compose-unstyled 的 Scrim 作用域限制),
 * 外观(背景色/圆角/边缘)全部读 MaterialTheme colorScheme。
 *
 * API 贴近 M3 AlertDialog: [onDismissRequest]、[confirmButton]、[dismissButton]、[title]、[text]。
 */
@Composable
fun BloomDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)? = null,
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val show = remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (show.value) 1f else 0f,
        animationSpec = tween(150),
        label = "bloomDialogAlpha",
    )
    LaunchedEffect(Unit) {
        show.value = true
    }
    if (show.value || alpha > 0f) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Scrim overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.scrim.copy(alpha = (0.45f * alpha)))
                    .clickable { show.value = false }
                    .widthIn(min = 280.dp, max = 560.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = modifier
                        .padding(16.dp)
                        .background(scheme.surfaceContainer, RoundedCornerShape(24.dp))
                        .widthIn(min = 280.dp, max = 560.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        title?.invoke()
                        text?.invoke()
                        content?.invoke()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            dismissButton?.invoke()
                            confirmButton?.invoke()
                        }
                    }
                }
            }
        }
        LaunchedEffect(alpha) {
            if (alpha == 0f) {
                onDismissRequest()
            }
        }
    }
}