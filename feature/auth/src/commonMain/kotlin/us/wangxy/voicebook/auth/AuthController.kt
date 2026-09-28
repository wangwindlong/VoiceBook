package us.wangxy.voicebook.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地假账号体系：注册/登录/重置密码全部在本地的账号表上完成校验，返回 null 表示
 * 成功、否则为可直接展示的中文错误信息。接后端时保持方法签名不变，把实现换成网络
 * 请求即可，页面层与跳转逻辑不用动。
 */
class AuthController(private val store: AuthStore) {
    private val stateFlow = MutableStateFlow(store.load())
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    fun login(username: String, password: String): String? {
        val name = username.trim()
        if (name.isEmpty()) return "请输入用户名"
        if (password.isEmpty()) return "请输入密码"
        val account = stateFlow.value.accounts.firstOrNull { it.username == name }
            ?: return "账号不存在，请先注册"
        if (account.password != password) return "密码错误"
        update(stateFlow.value.copy(currentUser = AuthUser(account.username, account.nickname)))
        return null
    }

    fun register(username: String, nickname: String, password: String, confirm: String): String? {
        val name = username.trim()
        if (name.isEmpty()) return "请输入用户名"
        if (name.contains('\t') || name.contains('\n')) return "用户名不能包含制表符或换行"
        if (stateFlow.value.accounts.any { it.username == name }) return "该用户名已被注册"
        if (password.length < PASSWORD_MIN_LENGTH) return "密码至少 $PASSWORD_MIN_LENGTH 位"
        if (password != confirm) return "两次输入的密码不一致"
        val nick = nickname.trim().ifEmpty { name }
        update(
            stateFlow.value.copy(
                accounts = stateFlow.value.accounts + LocalAccount(name, nick, password),
                currentUser = AuthUser(name, nick),
            ),
        )
        return null
    }

    fun resetPassword(username: String, newPassword: String, confirm: String): String? {
        val name = username.trim()
        val index = stateFlow.value.accounts.indexOfFirst { it.username == name }
        if (index < 0) return "账号不存在"
        if (newPassword.length < PASSWORD_MIN_LENGTH) return "密码至少 $PASSWORD_MIN_LENGTH 位"
        if (newPassword != confirm) return "两次输入的密码不一致"
        val account = stateFlow.value.accounts[index]
        update(
            stateFlow.value.copy(
                accounts = stateFlow.value.accounts.toMutableList().apply {
                    set(index, account.copy(password = newPassword))
                },
            ),
        )
        return null
    }

    fun logout() = update(stateFlow.value.copy(currentUser = null))

    private fun update(value: AuthState) {
        stateFlow.value = value
        store.save(value)
    }

    private companion object {
        const val PASSWORD_MIN_LENGTH = 6
    }
}
