package us.wangxy.voicebook.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.koin.mp.KoinPlatform
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

actual fun createThemeStore(): ThemeStore {
    val context = KoinPlatform.getKoin().get<Context>()
    return AndroidThemeStore(context.applicationContext)
}

private class AndroidThemeStore(context: Context) : ThemeStore {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val skinListSerializer = ListSerializer(SkinData.serializer())

    override fun load(): ThemePreference = ThemePreference(
        mode = prefs.getString(KEY_MODE, null).toMode(),
        skin = prefs.getString(KEY_SKIN, null).toSkinId(),
    )

    override fun save(preference: ThemePreference) {
        prefs.edit {
            putString(KEY_MODE, preference.mode.name)
                .putString(KEY_SKIN, preference.skin)
        }
    }

    override fun loadCustomSkins(): List<SkinData> {
        val jsonString = prefs.getString(KEY_CUSTOM_SKINS, null)
        return if (jsonString.isNullOrBlank()) {
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
        prefs.edit {
            putString(KEY_CUSTOM_SKINS, json.encodeToString(skinListSerializer, skins))
        }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SKIN = "skin"
        const val KEY_CUSTOM_SKINS = "custom_skins"
    }
}

@Composable
actual fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun SyncSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}
