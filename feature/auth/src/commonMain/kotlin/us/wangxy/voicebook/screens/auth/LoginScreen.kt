package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.bloom.BloomButton
import us.wangxy.voicebook.bloom.BloomButtonStyle
import us.wangxy.voicebook.bloom.BloomShadow
import us.wangxy.voicebook.bloom.BloomTextButton

/**
 * 登录页：用户名 + 密码，经 BFF 换取 Authelia token。成功后由 [onLoginSuccess] 回到主页；
 * 「去注册」压栈注册页（服务端关闭注册时隐藏），「忘记密码」压栈说明页。
 * 「服务器」折叠区可修改 BFF 地址，便于内网 / 测试环境切换。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onToRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    val authState by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable(authState.serverUrl) { mutableStateOf(authState.serverUrl) }

    LaunchedEffect(Unit) { viewModel.loadConfig() }

    AuthScaffold(title = "登录", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("VoiceBook", style = MaterialTheme.typography.headlineMedium)
        Text(
            "一个账号登录书库、资讯与评论",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; viewModel.clearError() },
            label = { Text("用户名") },
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearError() },
            label = "密码",
            enabled = !form.busy,
        )

        AuthErrorText(form.error)

        BloomButton(
            onClick = {
                if (serverUrl != authState.serverUrl) viewModel.setServerUrl(serverUrl)
                viewModel.login(username, password, onLoginSuccess)
            },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            style = BloomButtonStyle.Highlight,
            shadow = BloomShadow.Soft,
        ) { Text(if (form.busy) "登录中…" else "登录") }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BloomTextButton(onClick = onForgotPassword) { Text("忘记密码？") }
            if (config?.registrationEnabled != false) {
                BloomTextButton(onClick = onToRegister) { Text("没有账号？去注册") }
            }
        }

        BloomTextButton(onClick = { showServer = !showServer }, modifier = Modifier.padding(top = 16.dp)) {
            Text(if (showServer) "收起服务器设置" else "服务器：${authState.serverUrl}")
        }
        if (showServer) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("BFF 地址") },
                singleLine = true,
                enabled = !form.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            BloomTextButton(onClick = { viewModel.setServerUrl(serverUrl) }) { Text("保存地址") }
        }
    }
}
