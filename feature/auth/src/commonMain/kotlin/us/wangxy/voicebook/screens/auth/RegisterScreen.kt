package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.auth.AuthController

/**
 * 注册页：用户名 + 邮箱 + 昵称（可选）+ 密码 + 确认密码。BFF 在 LLDAP 建号并开通各组件，
 * 随后自动登录，由 [onRegisterSuccess] 回到主页；「已有账号」返回登录页。
 */
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onToLogin: () -> Unit,
    onRegisterSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthScaffold(title = "注册", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("创建账号", style = MaterialTheme.typography.headlineMedium)
        Text(
            "用户名 3-32 位，以小写字母开头；密码至少 ${AuthController.PASSWORD_MIN_LENGTH} 位且包含字母和数字",
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
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearError() },
            label = { Text("邮箱") },
            singleLine = true,
            enabled = !form.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it; viewModel.clearError() },
            label = { Text("昵称（可选）") },
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AuthFieldSpacing))
        PasswordField(password, { password = it; viewModel.clearError() }, "密码", enabled = !form.busy)
        Spacer(Modifier.height(AuthFieldSpacing))
        PasswordField(confirm, { confirm = it; viewModel.clearError() }, "确认密码", enabled = !form.busy)

        AuthErrorText(form.error)

        Button(
            onClick = { viewModel.register(username, nickname, email, password, confirm, onRegisterSuccess) },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
        ) { Text(if (form.busy) "注册中…" else "注册") }

        TextButton(
            onClick = onToLogin,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        ) { Text("已有账号？返回登录") }
    }
}
