package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.Json

actual fun createThemeStore(): ThemeStore = WebThemeStore

private object WebThemeStore : ThemeStore {
    private var preference = ThemePreference()
    private var customSkins: List<SkinData> = emptyList()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    override fun load(): ThemePreference = preference

    override fun save(preference: ThemePreference) {
        this.preference = preference
    }

    override fun loadCustomSkins(): List<SkinData> = customSkins

    override fun saveCustomSkins(skins: List<SkinData>) {
        customSkins = skins
    }
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
