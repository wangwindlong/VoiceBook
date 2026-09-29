package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.reader.api.CalibreServerAccount

@Composable
internal fun MineServerSection(
    signedIn: Boolean,
    savedServers: List<CalibreServerAccount>,
    currentBaseUrl: String?,
    url: String,
    onUrlChange: (String) -> Unit,
    user: String,
    onUserChange: (String) -> Unit,
    pass: String,
    onPassChange: (String) -> Unit,
    testing: Boolean,
    testResult: String?,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    SectionTitle("calibre-web 服务器")
    if (signedIn) {
        Text(
            "已登录统一账号：书城与书架经统一网关访问，下面的服务器在退出登录后生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    savedServers.forEach { account ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                account.label + if (account.baseUrl == currentBaseUrl) "（当前）" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onActivate(account.id) },
            )
            IconButton(onClick = { onDelete(account.id) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (savedServers.isNotEmpty()) HorizontalDivider()
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text("地址（http://ip:端口）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user,
        onValueChange = onUserChange,
        label = { Text("用户名（需 OPDS/下载权限）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pass,
        onValueChange = onPassChange,
        label = { Text("密码") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = url.isNotBlank() && !testing,
        ) {
            if (testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
        }
        Button(
            onClick = onSave,
            enabled = url.isNotBlank(),
        ) { Text("保存") }
        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (it == "连接成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}
