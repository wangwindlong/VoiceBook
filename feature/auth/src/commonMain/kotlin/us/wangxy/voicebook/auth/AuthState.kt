package us.wangxy.voicebook.auth

/** BFF used until the user edits the address on the login page. */
const val DEFAULT_BFF_URL = "https://nas.wangyl.work:8462"

/** 当前登录用户（来自 BFF 的 /api/auth/me）。 */
data class AuthUser(
    val username: String,
    val nickname: String,
    val email: String? = null,
)

/** Authelia 签发、经 BFF 转交的 token；[expiresAtEpochSeconds] 为 access token 的过期时刻。 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
)

/** 认证模块的完整状态：BFF 地址 + 当前用户 + 会话。三者要么同时为空要么同时存在（地址除外）。 */
data class AuthState(
    val serverUrl: String = DEFAULT_BFF_URL,
    val currentUser: AuthUser? = null,
    val session: AuthSession? = null,
)

/**
 * 落盘格式：一行一个 `key=value`，值里的换行被替换为空格（昵称是唯一可能含换行的字段）。
 * 旧版本地假账号格式（`current=` 与制表符行）不认识，解码后等同于未登录。
 */
internal fun AuthState.encode(): String = buildString {
    fun put(key: String, value: String?) {
        if (value != null) append(key).append('=').append(value.replace('\n', ' ')).append('\n')
    }
    put("server", serverUrl)
    currentUser?.let {
        put("user", it.username)
        put("nickname", it.nickname)
        put("email", it.email)
    }
    session?.let {
        put("access", it.accessToken)
        put("refresh", it.refreshToken)
        put("expires", it.expiresAtEpochSeconds.toString())
    }
}

internal fun decodeAuthState(raw: String): AuthState {
    val values = raw.lineSequence()
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val server = values["server"]?.takeIf { it.isNotBlank() } ?: DEFAULT_BFF_URL
    val username = values["user"]?.takeIf { it.isNotBlank() }
    val access = values["access"]?.takeIf { it.isNotBlank() }
    if (username == null || access == null) return AuthState(serverUrl = server)
    return AuthState(
        serverUrl = server,
        currentUser = AuthUser(username, values["nickname"] ?: username, values["email"]),
        session = AuthSession(
            accessToken = access,
            refreshToken = values["refresh"]?.takeIf { it.isNotBlank() },
            expiresAtEpochSeconds = values["expires"]?.toLongOrNull() ?: 0,
        ),
    )
}
