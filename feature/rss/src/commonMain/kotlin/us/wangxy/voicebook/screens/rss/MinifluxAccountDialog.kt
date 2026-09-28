package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssSyncMode

@Composable
internal fun MinifluxAccountDialog(
    initial: RssAccountModel?,
    testing: Boolean,
    testResult: String?,
    onTest: (String, String) -> Unit,
    onSave: (RssAccountModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initial?.serverUrl ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Miniflux 账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址（https://...）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Token（设置 → API Keys）") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onTest(serverUrl.trim(), token.trim()) },
                        enabled = serverUrl.isNotBlank() && token.isNotBlank() && !testing,
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RssAccountModel(
                            mode = RssSyncMode.Miniflux,
                            serverUrl = serverUrl.trim(),
                            token = token.trim(),
                            lastEntryId = initial?.lastEntryId,
                            lastSyncedAt = initial?.lastSyncedAt ?: 0L,
                        ),
                    )
                },
                enabled = serverUrl.isNotBlank() && token.isNotBlank(),
            ) { Text("保存并同步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
