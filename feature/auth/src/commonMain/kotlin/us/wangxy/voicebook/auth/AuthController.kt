package us.wangxy.voicebook.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.wangxy.voicebook.bff.BffApiException
import us.wangxy.voicebook.bff.BffAuthApi
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.contract.AuthConfigResponse
import us.wangxy.voicebook.bff.contract.AuthTokens
import us.wangxy.voicebook.bff.contract.ChangePasswordRequest
import us.wangxy.voicebook.bff.contract.LoginRequest
import us.wangxy.voicebook.bff.contract.MeResponse
import us.wangxy.voicebook.bff.contract.RegisterRequest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 账号体系的唯一入口，全部走 BFF：登录 / 注册 / 改密 / 退出，以及给其它网络请求用的
 * [validAccessToken]（临近过期时用 refresh token 静默续期）。各操作返回 null 表示成功，
 * 否则为可直接展示的中文错误信息。
 */
class AuthController(
    private val api: BffAuthApi,
    private val store: AuthStore,
    private val nowEpochSeconds: () -> Long = ::currentEpochSeconds,
) : BffSession {
    private val stateFlow = MutableStateFlow(store.load())
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    private val refreshMutex = Mutex()

    /** The BFF this app talks to; also the base for the Miniflux (资讯) channel. */
    override fun baseUrl(): String = stateFlow.value.serverUrl

    private val userFlow = MutableStateFlow(stateFlow.value.signedInUsername())
    override val signedInUser: StateFlow<String?> = userFlow.asStateFlow()

    /** Access token for other BFF calls, silently refreshed when near expiry. */
    override suspend fun accessToken(): String? = validAccessToken()

    override fun currentAccessToken(): String? = stateFlow.value.session?.accessToken

    fun setServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isNotEmpty() && trimmed != stateFlow.value.serverUrl) {
            update(stateFlow.value.copy(serverUrl = trimmed))
        }
    }

    /** 公开配置（是否开放注册、找回密码链接）；取不到时返回 null，页面按默认展示。 */
    suspend fun loadConfig(): AuthConfigResponse? = try {
        api.config(server())
    } catch (e: BffApiException) {
        null
    }

    suspend fun login(username: String, password: String): String? {
        val name = username.trim()
        if (name.isEmpty()) return "请输入用户名"
        if (password.isEmpty()) return "请输入密码"
        return attempt {
            val response = api.login(server(), LoginRequest(name, password))
            signIn(response.tokens, response.user)
        }
    }

    /** 注册成功后自动登录；注册成功但自动登录失败时提示用户手动登录。 */
    suspend fun register(username: String, nickname: String, email: String, password: String, confirm: String): String? {
        val name = username.trim()
        if (name.isEmpty()) return "请输入用户名"
        if (email.isBlank()) return "请输入邮箱"
        passwordProblem(password, confirm)?.let { return it }
        attempt {
            api.register(server(), RegisterRequest(name, email.trim(), password, nickname.trim().ifEmpty { null }))
        }?.let { return it }
        return login(name, password)?.let { "注册成功，但自动登录失败：$it" }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String, confirm: String): String? {
        if (oldPassword.isEmpty()) return "请输入当前密码"
        passwordProblem(newPassword, confirm)?.let { return it }
        if (oldPassword == newPassword) return "新密码不能与当前密码相同"
        val token = validAccessToken() ?: return "登录已过期，请重新登录"
        return attempt { api.changePassword(server(), token, ChangePasswordRequest(oldPassword, newPassword)) }
    }

    /** 本地立即登出；再尽力通知 BFF 吊销 token（失败不影响本地状态）。 */
    suspend fun logout() {
        val session = stateFlow.value.session
        signOut()
        if (session != null) {
            try {
                api.logout(server(), session.accessToken, session.refreshToken)
            } catch (e: BffApiException) {
                // Offline or BFF down: tokens simply expire on their own.
            }
        }
    }

    /**
     * 可用的 access token；距离过期不足 [REFRESH_MARGIN_SECONDS] 秒时先续期。
     * refresh token 失效会清掉会话并返回 null；网络异常时返回现有 token，交给调用方的 401 处理。
     */
    suspend fun validAccessToken(): String? = refreshMutex.withLock {
        val session = stateFlow.value.session ?: return null
        if (session.expiresAtEpochSeconds - nowEpochSeconds() > REFRESH_MARGIN_SECONDS) return session.accessToken
        val refreshToken = session.refreshToken ?: run {
            signOut()
            return null
        }
        try {
            val tokens = api.refresh(server(), refreshToken)
            val refreshed = tokens.toSession(fallbackRefresh = refreshToken)
            update(stateFlow.value.copy(session = refreshed))
            refreshed.accessToken
        } catch (e: BffApiException) {
            if (e.isUnauthorized) {
                signOut()
                null
            } else {
                session.accessToken
            }
        }
    }

    private fun signIn(tokens: AuthTokens, user: MeResponse) {
        update(
            stateFlow.value.copy(
                currentUser = AuthUser(user.username, user.displayName?.takeIf { it.isNotBlank() } ?: user.username, user.email),
                session = tokens.toSession(fallbackRefresh = null),
            ),
        )
    }

    private fun signOut() = update(stateFlow.value.copy(currentUser = null, session = null))

    private fun AuthTokens.toSession(fallbackRefresh: String?) = AuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken ?: fallbackRefresh,
        expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
    )

    private fun server() = stateFlow.value.serverUrl

    private inline fun attempt(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: BffApiException) {
        if (e.isUnauthorized && e.code != "INVALID_CREDENTIALS" && stateFlow.value.session != null) signOut()
        e.message
    }

    private fun passwordProblem(password: String, confirm: String): String? = when {
        password.length < PASSWORD_MIN_LENGTH -> "密码至少 $PASSWORD_MIN_LENGTH 位"
        password.none { it.isLetter() } || password.none { it.isDigit() } -> "密码需同时包含字母和数字"
        password != confirm -> "两次输入的密码不一致"
        else -> null
    }

    private fun update(value: AuthState) {
        stateFlow.value = value
        userFlow.value = value.signedInUsername()
        store.save(value)
    }

    companion object {
        /** 与 BFF 默认的 PASSWORD_MIN_LENGTH 一致；最终以服务端校验为准。 */
        const val PASSWORD_MIN_LENGTH = 8
        private const val REFRESH_MARGIN_SECONDS = 60L
    }
}

private fun AuthState.signedInUsername(): String? = currentUser?.username?.takeIf { session != null }

@OptIn(ExperimentalTime::class)
private fun currentEpochSeconds(): Long = Clock.System.now().epochSeconds
