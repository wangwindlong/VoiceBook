package us.wangxy.voicebook.screens.auth

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import us.wangxy.voicebook.auth.AuthController
import us.wangxy.voicebook.auth.AuthState

/**
 * 登录/注册/重置密码共用一个 ViewModel：本地假账号体系是同步调用，返回 null 即成功，
 * 否则为错误文案。接后端时在这里把同步调用换成挂起请求 + loading 状态即可。
 */
class AuthViewModel(private val auth: AuthController) : ViewModel() {
    val state: StateFlow<AuthState> = auth.state

    fun login(username: String, password: String): String? = auth.login(username, password)

    fun register(username: String, nickname: String, password: String, confirm: String): String? =
        auth.register(username, nickname, password, confirm)

    fun resetPassword(username: String, newPassword: String, confirm: String): String? =
        auth.resetPassword(username, newPassword, confirm)

    fun logout() = auth.logout()
}
