package us.wangxy.voicebook.auth

import android.content.Context
import org.koin.mp.KoinPlatform

actual fun createAuthStore(): AuthStore {
    val context = KoinPlatform.getKoin().get<Context>()
    return AndroidAuthStore(context.applicationContext)
}

private class AndroidAuthStore(context: Context) : AuthStore {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    override fun load(): AuthState = decodeAuthState(prefs.getString(KEY, null) ?: "")

    override fun save(state: AuthState) {
        prefs.edit().putString(KEY, state.encode()).apply()
    }

    private companion object {
        const val KEY = "auth_state"
    }
}
