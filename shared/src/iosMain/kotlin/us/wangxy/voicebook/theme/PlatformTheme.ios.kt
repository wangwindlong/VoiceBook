package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import platform.Foundation.NSUserDefaults

actual fun createThemeStore(): ThemeStore = IosThemeStore

private object IosThemeStore : ThemeStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): ThemePreference = ThemePreference(
        mode = defaults.stringForKey(KEY_MODE).toMode(),
        skin = defaults.stringForKey(KEY_SKIN).toSkin(),
    )

    override fun save(preference: ThemePreference) {
        defaults.setObject(preference.mode.name, forKey = KEY_MODE)
        defaults.setObject(preference.skin.name, forKey = KEY_SKIN)
    }

    private const val KEY_MODE = "theme_mode"
    private const val KEY_SKIN = "theme_skin"
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
