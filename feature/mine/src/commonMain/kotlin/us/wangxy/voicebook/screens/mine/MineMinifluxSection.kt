package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.compose.ui.text.input.PasswordVisualTransformation
import us.wangxy.voicebook.screens.rss.FeedsUiState
import us.wangxy.voicebook.rss.RssSyncMode

@Composable
internal fun MineMinifluxSection(
    state: FeedsUiState,
    signedIn: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val savedAccounts = state.savedAccounts.filter { it.mode == RssSyncMode.Miniflux }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isExpanded = expanded || savedAccounts.isEmpty()
    AccountSectionTitle("Miniflux 账号", isExpanded) { expanded = !isExpanded }
    savedAccounts.forEach { account ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                account.serverUrl.orEmpty() + if (account.serverUrl == state.account?.serverUrl && account.token == state.account?.token) "（当前）" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { expanded = true; onActivate(account.id) },
            )
            IconButton(onClick = { onDelete(account.id) }) {
                Icon(Icons.Filled.Close, "删除", modifier = Modifier.size(18.dp))
            }
        }
    }
    if (!isExpanded) return
    if (signedIn) Text(
        "已登录统一账号：资讯使用统一账号，下面的独立账号在退出登录后生效。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (savedAccounts.isNotEmpty()) HorizontalDivider()
    OutlinedTextField(url, onUrlChange, label = { Text("服务器地址（https://...）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        token, onTokenChange,
        label = { Text("API Token（设置 → API Keys）") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onTest, enabled = url.isNotBlank() && token.isNotBlank() && !state.testing) {
            Box(contentAlignment = Alignment.Center) {
                Text("测试连接", modifier = Modifier.alpha(if (state.testing) 0f else 1f))
                if (state.testing) CircularProgressIndicator(Modifier.size(16.dp))
            }
        }
        Button(onClick = { onSave(); expanded = false }, enabled = url.isNotBlank() && token.isNotBlank()) { Text("保存") }
        state.testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (it == "连接成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
