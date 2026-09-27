package us.wangxy.voicebook.screens.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.screens.EmptyScreenContent

/**
 * AI tab 占位页：待移植姊妹项目 AVAssistance 的聊天页面。
 */
@Composable
fun AiScreen(
    onOpenSidebar: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Filled.Build, contentDescription = "工具箱")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("AI", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Text(
            "聊天功能开发中",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        EmptyScreenContent(Modifier.fillMaxSize())
        Spacer(Modifier.weight(1f))
    }
}
