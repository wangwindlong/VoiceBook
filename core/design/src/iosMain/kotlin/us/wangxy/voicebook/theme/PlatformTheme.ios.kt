package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import platform.Foundation.NSUserDefaults
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

actual fun createThemeStore(): ThemeStore = IosThemeStore

private object IosThemeStore : ThemeStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val skinListSerializer = ListSerializer(SkinData.serializer())

    override fun load(): ThemePreference = ThemePreference(
        mode = defaults.stringForKey(KEY_MODE).toMode(),
        skin = defaults.stringForKey(KEY_SKIN).toSkinId(),
    )

    override fun save(preference: ThemePreference) {
        defaults.setObject(preference.mode.name, forKey = KEY_MODE)
        defaults.setObject(preference.skin, forKey = KEY_SKIN)
    }

    override fun loadCustomSkins(): List<SkinData> {
        val jsonString = defaults.stringForKey(KEY_CUSTOM_SKINS) ?: ""
        return if (jsonString.isBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(skinListSerializer, jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override fun saveCustomSkins(skins: List<SkinData>) {
        defaults.setObject(json.encodeToString(skinListSerializer, skins), forKey = KEY_CUSTOM_SKINS)
    }

    private const val KEY_MODE = "theme_mode"
    private const val KEY_SKIN = "theme_skin"
    private const val KEY_CUSTOM_SKINS = "theme_custom_skins"
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
