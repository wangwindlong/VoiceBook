package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import java.util.prefs.Preferences

actual fun createThemeStore(): ThemeStore = JvmThemeStore

private object JvmThemeStore : ThemeStore {
    private val prefs = Preferences.userRoot().node("us.wangxy.voicebook")

    override fun load(): ThemePreference = ThemePreference(
        mode = prefs.get("mode", null).toMode(),
        skin = prefs.get("skin", null).toSkin(),
    )

    override fun save(preference: ThemePreference) {
        prefs.put("mode", preference.mode.name)
        prefs.put("skin", preference.skin.name)
    }
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
