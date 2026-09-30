package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import us.wangxy.voicebook.bloom.BloomShape
import us.wangxy.voicebook.bloom.LocalBloomTokens

/**
 * Bloom 对话框。走系统 [Dialog] 窗口，盖在当前界面上，圆角和底色跟当前皮肤走。
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
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBloomTokens.current
    Dialog(onDismissRequest = onDismissRequest) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            Column(
                modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .heightIn(max = 560.dp)
                    .background(
                        scheme.surfaceContainer,
                        BloomShape(28.dp, 28.dp, 28.dp, 28.dp, tokens.smoothing),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                title?.invoke()
                text?.invoke()
                content?.invoke()
                if (dismissButton != null || confirmButton != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        dismissButton?.invoke()
                        confirmButton?.invoke()
                    }
                }
            }
        }
    }
}
