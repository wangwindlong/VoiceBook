package us.wangxy.voicebook.auth

import java.util.prefs.Preferences

actual fun createAuthStore(): AuthStore = JvmAuthStore

private object JvmAuthStore : AuthStore {
    private val prefs = Preferences.userRoot().node("us.wangxy.voicebook.auth")

    override fun load(): AuthState = decodeAuthState(prefs.get(KEY, "") ?: "")

    override fun save(state: AuthState) {
        prefs.put(KEY, state.encode())
    }

    private const val KEY = "auth_state"
}
