package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.screens.auth.AuthViewModel
import us.wangxy.voicebook.ui.widget.GlassSurface

/**
 * 「我的」：账户（登录/注册入口）、服务器设置（迁自原书城设置弹窗）、外观、崩溃日志、
 * 调试入口（语音页、Museum 示例、Twine / Bloom 组件演示）。
 */
@Composable
fun MineScreen(
    onOpenVoiceDebug: () -> Unit = {},
    onOpenMuseumDemo: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onOpenTwineDemo: () -> Unit = {},
    onOpenBloomDemo: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
    onOpenChangePassword: () -> Unit = {},
    onBack: (() -> Unit)? = null,
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
    var showProfile by remember { mutableStateOf(false) }
    var showCrashLog by remember { mutableStateOf(false) }
    var crashEntries by remember { mutableStateOf(CrashReporter.readAll()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGlassGroup {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                IconButton(onClick = onBack ?: onOpenSidebar) {
                    Icon(if (onBack != null) androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Build, contentDescription = if (onBack != null) "返回" else "工具箱")
                }
                Spacer(Modifier.size(8.dp))
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }
        }

        SettingsGlassGroup {
            AppearancePanel()
        }

        SettingsGlassGroup {
            MineAccountSection(
                user = authState.currentUser,
                onOpenLogin = onOpenLogin,
                onChangePassword = onOpenChangePassword,
                onLogout = authViewModel::logout,
            )
        }

        SettingsGlassGroup {
            MineServerSection(
                signedIn = authState.session != null,
                savedServers = savedServers,
                currentBaseUrl = server?.baseUrl,
                url = url,
                onUrlChange = { url = it },
                user = user,
                onUserChange = { user = it },
                pass = pass,
                onPassChange = { pass = it },
                testing = testing,
                testResult = testResult,
                onActivate = viewModel::activateServerAccount,
                onDelete = viewModel::deleteServerAccount,
                onTest = { viewModel.testConnection(CalibreServer(url.trim(), user.trim(), pass)) },
                onSave = { viewModel.saveServer(CalibreServer(url.trim(), user.trim(), pass)) },
            )
        }

        SettingsGlassGroup { TextButton(onClick = { showProfile = true }) { Text("兴趣画像") } }

        SettingsGlassGroup {
            SectionTitle("崩溃日志")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { crashEntries = CrashReporter.readAll(); showCrashLog = true }) { Text("查看") }
                Text(
                    "${crashEntries.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGlassGroup {
            SectionTitle("调试")
            DebugRow("语音调试页面", onOpenVoiceDebug)
            DebugRow("Museum 示例", onOpenMuseumDemo)
            // TODO: Twine 组件铺开到各页面后,连同演示页一起移除这个临时入口
            DebugRow("Twine 组件演示", onOpenTwineDemo)
            DebugRow("Bloom 组件演示", onOpenBloomDemo)
        }
        Spacer(Modifier.size(24.dp))
    }

    if (showProfile) InterestProfileSheet(onDismiss = { showProfile = false })
    if (showCrashLog) {
        CrashLogDialog(
            entries = crashEntries,
            onClear = {
                CrashReporter.clear()
                crashEntries = emptyList()
                showCrashLog = false
            },
            onDismiss = { showCrashLog = false },
        )
    }
}

@Composable
private fun SettingsGlassGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}
