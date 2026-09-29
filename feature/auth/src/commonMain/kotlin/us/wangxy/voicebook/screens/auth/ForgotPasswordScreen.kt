package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * 找回密码：密码只存在统一认证（LLDAP）里，重置要走邮件验证，由 Authelia 的网页完成。
 * BFF 配置了 PASSWORD_RESET_URL 时提供跳转按钮，否则提示联系管理员。
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onResetSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.loadConfig() }

    AuthScaffold(title = "找回密码", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("重置密码", style = MaterialTheme.typography.headlineMedium)
        val resetUrl = config?.passwordResetUrl
        Text(
            if (resetUrl != null) {
                "将在浏览器中打开统一认证的重置页面，按邮件提示设置新密码后回到这里登录。"
            } else {
                "当前服务器未开放自助重置，请联系管理员重置密码。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        if (resetUrl != null) {
            Button(
                onClick = { uriHandler.openUri(resetUrl) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("打开重置页面") }
        }
        Button(
            onClick = onResetSuccess,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
        ) { Text("返回登录") }
    }
}
