package us.wangxy.voicebook.auth

/** 认证状态的跨启动持久化，模式同 core/design 的 UiPrefsStore。 */
interface AuthStore {
    fun load(): AuthState
    fun save(state: AuthState)
}

expect fun createAuthStore(): AuthStore
