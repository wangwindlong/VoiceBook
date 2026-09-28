package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.screens.auth.AuthViewModel
import us.wangxy.voicebook.theme.ThemeBar

/**
 * 「我的」：账户（登录/注册入口）、服务器设置（迁自原书城设置弹窗）、外观、崩溃日志、
 * 调试入口（语音页、Museum 示例）。
 */
@Composable
fun MineScreen(
    onOpenVoiceDebug: () -> Unit = {},
    onOpenMuseumDemo: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onOpenTwineDemo: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
) {
    val viewModel = koinViewModel<MineViewModel>()
    val authViewModel = koinViewModel<AuthViewModel>()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val savedServers by viewModel.savedServers.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()

    var url by remember(server) { mutableStateOf(server?.baseUrl ?: "") }
    var user by remember(server) { mutableStateOf(server?.username ?: "") }
    var pass by remember(server) { mutableStateOf(server?.password ?: "") }
    var showCrashLog by remember { mutableStateOf(false) }
    val crashEntries = remember { CrashReporter.readAll() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Filled.Build, contentDescription = "工具箱")
            }
            Spacer(Modifier.size(8.dp))
            Text("我的", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("账户")
        val authUser = authState.currentUser
        if (authUser == null) {
            DebugRow("登录 / 注册", onOpenLogin)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(authUser.nickname, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "@${authUser.username}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = authViewModel::logout) { Text("退出登录") }
            }
        }

        SectionTitle("calibre-web 服务器")
        savedServers.forEach { account ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    account.label + if (account.baseUrl == server?.baseUrl) "（当前）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.activateServerAccount(account.id) },
                )
                IconButton(onClick = { viewModel.deleteServerAccount(account.id) }) {
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
            onValueChange = { url = it },
            label = { Text("地址（http://ip:端口）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名（需 OPDS/下载权限）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
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
                onClick = { viewModel.testConnection(CalibreServer(url.trim(), user.trim(), pass)) },
                enabled = url.isNotBlank() && !testing,
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
            }
            Button(
                onClick = { viewModel.saveServer(CalibreServer(url.trim(), user.trim(), pass)) },
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

        SectionTitle("外观")
        ThemeBar()

        SectionTitle("崩溃日志")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showCrashLog = true }) { Text("查看") }
            Text(
                "${crashEntries.size} 条记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("调试")
        DebugRow("语音调试页面", onOpenVoiceDebug)
        DebugRow("Museum 示例", onOpenMuseumDemo)
        // TODO: Twine 组件铺开到各页面后,连同演示页一起移除这个临时入口
        DebugRow("Twine 组件演示", onOpenTwineDemo)

        Spacer(Modifier.size(24.dp))
    }

    if (showCrashLog) {
        AlertDialog(
            onDismissRequest = { showCrashLog = false },
            title = { Text("崩溃日志") },
            text = {
                if (crashEntries.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(crashEntries.asReversed(), key = { it.hashCode() }) { line ->
                            Text(line, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CrashReporter.clear()
                    showCrashLog = false
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showCrashLog = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun DebugRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("→", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
