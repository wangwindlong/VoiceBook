package us.wangxy.voicebook.auth

/**
 * 本地注册的账号（占位实现）：接后端后整个账号表删除，注册/登录改为网络请求。
 */
data class LocalAccount(
    val username: String,
    val nickname: String,
    val password: String,
)

/** 登录成功后的当前用户。后端接入后这里会补充 token 等字段。 */
data class AuthUser(
    val username: String,
    val nickname: String,
)

/** 认证模块的完整状态：已注册账号表 + 当前登录用户。 */
data class AuthState(
    val accounts: List<LocalAccount> = emptyList(),
    val currentUser: AuthUser? = null,
)

/**
 * 落盘格式（一行一条，\t 分隔，编码/解码都在 commonMain，平台 actual 只管字符串读写）：
 * ```
 * current=<username>        （未登录时为空）
 * <username>\t<nickname>\t<password>
 * ```
 */
internal fun AuthState.encode(): String = buildString {
    append("current=").append(currentUser?.username ?: "").append('\n')
    accounts.forEach {
        append(it.username).append('\t').append(it.nickname).append('\t').append(it.password).append('\n')
    }
}

internal fun decodeAuthState(raw: String): AuthState {
    var currentName: String? = null
    val accounts = mutableListOf<LocalAccount>()
    raw.lineSequence().forEach { line ->
        when {
            line.startsWith("current=") -> currentName = line.removePrefix("current=").ifEmpty { null }
            line.isNotEmpty() -> {
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    accounts += LocalAccount(parts[0], parts[1], parts.drop(2).joinToString("\t"))
                }
            }
        }
    }
    val current = currentName?.let { name ->
        accounts.firstOrNull { it.username == name }?.let { AuthUser(it.username, it.nickname) }
    }
    return AuthState(accounts = accounts, currentUser = current)
}
