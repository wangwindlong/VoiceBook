package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.ui.widget.BloomDialog

@Composable
internal fun CrashLogDialog(
    entries: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    BloomDialog(
        onDismissRequest = onDismiss,
        title = { Text("崩溃日志") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无记录", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries.asReversed(), key = { it.hashCode() }) { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
