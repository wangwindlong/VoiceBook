package us.wangxy.voicebook.auth

/** Web 端暂与 UiPrefs 同策略：仅内存保存，刷新后回到未登录。 */
actual fun createAuthStore(): AuthStore = WebAuthStore

private object WebAuthStore : AuthStore {
    private var state = AuthState()

    override fun load(): AuthState = state

    override fun save(state: AuthState) {
        this.state = state
    }
}
