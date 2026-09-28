package us.wangxy.voicebook.auth

import platform.Foundation.NSUserDefaults

actual fun createAuthStore(): AuthStore = IosAuthStore

private object IosAuthStore : AuthStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): AuthState = decodeAuthState(defaults.stringForKey(KEY) ?: "")

    override fun save(state: AuthState) {
        defaults.setObject(state.encode(), forKey = KEY)
    }

    private const val KEY = "auth_state"
}
