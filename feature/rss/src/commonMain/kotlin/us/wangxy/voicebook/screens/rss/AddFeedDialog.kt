package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AddFeedDialog(
    adding: Boolean,
    message: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅源地址（RSS / Atom）") },
                    singleLine = true,
                )
                if (adding) CircularProgressIndicator(Modifier.size(20.dp))
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !adding) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
