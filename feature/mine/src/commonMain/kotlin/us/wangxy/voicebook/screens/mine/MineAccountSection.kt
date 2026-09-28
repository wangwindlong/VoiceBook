package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.auth.AuthUser

@Composable
internal fun MineAccountSection(
    user: AuthUser?,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    SectionTitle("账户")
    if (user == null) {
        DebugRow("登录 / 注册", onOpenLogin)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(user.nickname, style = MaterialTheme.typography.bodyMedium)
            Text(
                "@${user.username}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLogout) { Text("退出登录") }
    }
}
