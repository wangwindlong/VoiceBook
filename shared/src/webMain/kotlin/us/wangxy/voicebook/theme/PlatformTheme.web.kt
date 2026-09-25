package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

actual fun createThemeStore(): ThemeStore = WebThemeStore

private object WebThemeStore : ThemeStore {
    private var preference = ThemePreference()

    override fun load(): ThemePreference = preference

    override fun save(preference: ThemePreference) {
        this.preference = preference
    }
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
