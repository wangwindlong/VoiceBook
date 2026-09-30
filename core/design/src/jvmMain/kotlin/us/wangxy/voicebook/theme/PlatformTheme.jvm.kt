package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import java.util.prefs.Preferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

actual fun createThemeStore(): ThemeStore = JvmThemeStore

private object JvmThemeStore : ThemeStore {
    private val prefs = Preferences.userRoot().node("us.wangxy.voicebook")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val skinListSerializer = ListSerializer(SkinData.serializer())

    override fun load(): ThemePreference = ThemePreference(
        mode = prefs.get("mode", null).toMode(),
        skin = prefs.get("skin", "system"),
    )

    override fun save(preference: ThemePreference) {
        prefs.put("mode", preference.mode.name)
        prefs.put("skin", preference.skin)
    }

    override fun loadCustomSkins(): List<SkinData> {
        val jsonString = prefs.get("custom_skins", "")
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
        prefs.put("custom_skins", json.encodeToString(skinListSerializer, skins))
    }
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) = Unit
