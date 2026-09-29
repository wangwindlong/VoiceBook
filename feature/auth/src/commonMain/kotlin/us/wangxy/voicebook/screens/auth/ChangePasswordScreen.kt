package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.auth.AuthController

/**
 * 修改密码：校验当前密码后由 BFF 写入 LLDAP，书库、资讯、评论同时生效。
 * 已登录会话不受影响；登录已过期时会被登出，[onSessionExpired] 负责跳去登录页。
 */
@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    val authState by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthScaffold(title = "修改密码", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("修改密码", style = MaterialTheme.typography.headlineMedium)
        Text(
            "新密码至少 ${AuthController.PASSWORD_MIN_LENGTH} 位且包含字母和数字，修改后所有服务同时生效",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        PasswordField(oldPassword, { oldPassword = it; viewModel.clearError() }, "当前密码", enabled = !form.busy)
        Spacer(Modifier.height(AuthFieldSpacing))
        PasswordField(newPassword, { newPassword = it; viewModel.clearError() }, "新密码", enabled = !form.busy)
        Spacer(Modifier.height(AuthFieldSpacing))
        PasswordField(confirm, { confirm = it; viewModel.clearError() }, "确认新密码", enabled = !form.busy)

        AuthErrorText(form.error)

        if (authState.session == null) {
            Button(
                onClick = onSessionExpired,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
            ) { Text("重新登录") }
        } else {
            Button(
                onClick = { viewModel.changePassword(oldPassword, newPassword, confirm, onChanged) },
                enabled = !form.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
            ) { Text(if (form.busy) "提交中…" else "确认修改") }
        }
    }
}
