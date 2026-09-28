package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

/**
 * 重置密码页（占位）：本地校验账号存在后直接改掉本地密码，成功即返回登录页。
 * 接后端后这里应改为「验证码/邮件链接」流程，页面结构不变。
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onResetSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    var username by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AuthScaffold(title = "重置密码", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("找回账号", style = MaterialTheme.typography.headlineMedium)
        Text(
            "输入用户名并设置新密码（本地演示，暂无验证码环节）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it; error = null },
            label = { Text("新密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = { Text("确认新密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            onClick = {
                val result = viewModel.resetPassword(username, newPassword, confirm)
                if (result == null) onResetSuccess() else error = result
            },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
        ) { Text("重置密码") }
    }
}
