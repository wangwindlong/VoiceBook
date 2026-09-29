package us.wangxy.voicebook.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.auth.AuthController
import us.wangxy.voicebook.auth.AuthState
import us.wangxy.voicebook.bff.contract.AuthConfigResponse

/** 表单提交状态：[busy] 期间按钮禁用，[error] 为最近一次失败的提示。 */
data class AuthFormState(val busy: Boolean = false, val error: String? = null)

/**
 * 登录 / 注册 / 改密 / 找回密码共用；每个页面各持有一个实例（koinViewModel 按导航项隔离），
 * 所以 [form] 不会在页面间串味。成功回调在主线程执行，可直接做导航。
 */
class AuthViewModel(private val auth: AuthController) : ViewModel() {
    val state: StateFlow<AuthState> = auth.state

    private val formFlow = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = formFlow.asStateFlow()

    private val configFlow = MutableStateFlow<AuthConfigResponse?>(null)
    val config: StateFlow<AuthConfigResponse?> = configFlow.asStateFlow()

    fun loadConfig() {
        viewModelScope.launch { configFlow.value = auth.loadConfig() }
    }

    fun setServerUrl(url: String) {
        auth.setServerUrl(url)
        loadConfig()
    }

    fun clearError() = formFlow.update { it.copy(error = null) }

    fun login(username: String, password: String, onSuccess: () -> Unit) =
        submit(onSuccess) { auth.login(username, password) }

    fun register(username: String, nickname: String, email: String, password: String, confirm: String, onSuccess: () -> Unit) =
        submit(onSuccess) { auth.register(username, nickname, email, password, confirm) }

    fun changePassword(oldPassword: String, newPassword: String, confirm: String, onSuccess: () -> Unit) =
        submit(onSuccess) { auth.changePassword(oldPassword, newPassword, confirm) }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    private fun submit(onSuccess: () -> Unit, action: suspend () -> String?) {
        if (formFlow.value.busy) return
        formFlow.value = AuthFormState(busy = true)
        viewModelScope.launch {
            val error = action()
            formFlow.value = AuthFormState(busy = false, error = error)
            if (error == null) onSuccess()
        }
    }
}
