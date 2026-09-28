package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

/**
 * 注册页：用户名 + 昵称（可选，默认同用户名）+ 密码 + 确认密码。注册成功即本地自动
 * 登录，由 [onRegisterSuccess] 直接回到主页；「已有账号」返回登录页。
 */
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onToLogin: () -> Unit,
    onRegisterSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AuthScaffold(title = "注册", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("创建账号", style = MaterialTheme.typography.headlineMedium)
        Text(
            "密码至少 6 位；昵称留空则与用户名相同",
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
            value = nickname,
            onValueChange = { nickname = it; error = null },
            label = { Text("昵称（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = { Text("确认密码") },
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
                val result = viewModel.register(username, nickname, password, confirm)
                if (result == null) onRegisterSuccess() else error = result
            },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
        ) { Text("注册") }

        TextButton(
            onClick = onToLogin,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        ) { Text("已有账号？返回登录") }
    }
}
