package us.wangxy.voicebook.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.bloom.BloomButton
import us.wangxy.voicebook.bloom.BloomButtonStyle
import us.wangxy.voicebook.bloom.BloomShadow
import us.wangxy.voicebook.bloom.BloomTextButton

/**
 * 登录页：用户名 + 密码。成功后由 [onLoginSuccess] 回到主页；「去注册」压栈注册页，
 * 「忘记密码」压栈重置页，返回键都能回到本页。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onToRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val viewModel = koinViewModel<AuthViewModel>()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AuthScaffold(title = "登录", onBack = onBack) {
        Spacer(Modifier.height(32.dp))
        Text("VoiceBook", style = MaterialTheme.typography.headlineMedium)
        Text(
            "登录后同步你的书架与阅读进度",
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
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                BloomTextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "隐藏" else "显示")
                }
            },
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

        BloomButton(
            onClick = {
                val result = viewModel.login(username, password)
                if (result == null) onLoginSuccess() else error = result
            },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            style = BloomButtonStyle.Highlight,
            shadow = BloomShadow.Soft,
        ) { Text("登录") }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BloomTextButton(onClick = onForgotPassword) { Text("忘记密码？") }
            BloomTextButton(onClick = onToRegister) { Text("没有账号？去注册") }
        }
    }
}
