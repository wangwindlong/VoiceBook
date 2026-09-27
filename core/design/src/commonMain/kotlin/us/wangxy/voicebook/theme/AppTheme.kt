package us.wangxy.voicebook.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

enum class AppSkin { System, Dynamic, Fold, Tide, Neon }

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.System,
    val skin: AppSkin = AppSkin.System,
)

interface ThemeStore {
    fun load(): ThemePreference
    fun save(preference: ThemePreference)
}

expect fun createThemeStore(): ThemeStore

@Composable
expect fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
expect fun SyncSystemBars(darkTheme: Boolean)

class ThemeController(private val store: ThemeStore) {
    private val preferenceState = MutableStateFlow(store.load())
    val preference: StateFlow<ThemePreference> = preferenceState.asStateFlow()

    fun setMode(mode: ThemeMode) = update(preferenceState.value.copy(mode = mode))

    fun setSkin(skin: AppSkin) = update(preferenceState.value.copy(skin = skin))

    private fun update(value: ThemePreference) {
        preferenceState.value = value
        store.save(value)
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController is missing")
}

private data class SkinSpec(
    val light: ColorScheme,
    val dark: ColorScheme,
    val shapes: Shapes,
    /** 手工调校的 Twine 令牌;为 null 时从 ColorScheme 派生(System/Dynamic)。 */
    val twineLight: TwineTokens? = null,
    val twineDark: TwineTokens? = null,
)

private fun corners(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int) =
    RoundedCornerShape(topStart.dp, topEnd.dp, bottomEnd.dp, bottomStart.dp)

private fun skinShapes(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int) = Shapes(
    extraSmall = corners(topStart / 3, topEnd / 3, bottomEnd / 3, bottomStart / 3),
    small = corners(topStart / 2, topEnd / 2, bottomEnd / 2, bottomStart / 2),
    medium = corners(topStart, topEnd, bottomEnd, bottomStart),
    large = corners(topStart + 8, topEnd + 4, bottomEnd + 10, bottomStart + 2),
    extraLarge = corners(topStart + 16, topEnd + 6, bottomEnd + 18, bottomStart + 4),
)

private val skins: Map<AppSkin, SkinSpec> = mapOf(
    AppSkin.System to SkinSpec(
        light = lightColorScheme(),
        dark = darkColorScheme(),
        shapes = Shapes(),
    ),
    // Dynamic generates both schemes from the current book cover's seed color;
    // the static entries here are only the fallback when no seed is available.
    AppSkin.Dynamic to SkinSpec(
        light = lightColorScheme(),
        dark = darkColorScheme(),
        shapes = Shapes(),
    ),
    AppSkin.Fold to SkinSpec(
        light = lightColorScheme(
            primary = Color(0xFF6B3A2A),
            onPrimary = Color(0xFFFFF8F0),
            secondary = Color(0xFF8A5A44),
            background = Color(0xFFF6E7D4),
            surface = Color(0xFFFFF3E4),
            surfaceVariant = Color(0xFFE7D3BC),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFE7B089),
            onPrimary = Color(0xFF3A2418),
            secondary = Color(0xFFD7A07A),
            background = Color(0xFF1C1410),
            surface = Color(0xFF2A1E18),
            surfaceVariant = Color(0xFF3D2C24),
        ),
        shapes = skinShapes(28, 4, 22, 6),
        // 折纸:泛黄的宣纸配深褐墨,强调是朱砂批注
        twineLight = TwineTokens(
            paper = Color(0xFFFFF8EC),
            paperEdge = Color(0xFFD8C4A5),
            ink = Color(0xFF3A2C1E),
            inkFaded = Color(0xFF8C7B66),
            inkAccent = Color(0xFF9C3B22),
            inkAccentMuted = Color(0xFF9C3B22).copy(alpha = 0.5f),
            highlight = Color(0xFFF5D76E).copy(alpha = 0.45f),
            passageCorner = 10.dp,
            passageIndent = 32.dp,
            pageGutter = 20.dp,
            revealDurationMillis = 300,
            revealEasing = defaultRevealEasing,
        ),
        twineDark = TwineTokens(
            paper = Color(0xFF241A14),
            paperEdge = Color(0xFF4A382B),
            ink = Color(0xFFE8D8C2),
            inkFaded = Color(0xFFA08D75),
            inkAccent = Color(0xFFE2814F),
            inkAccentMuted = Color(0xFFE2814F).copy(alpha = 0.5f),
            highlight = Color(0xFF8A6A1F).copy(alpha = 0.45f),
            passageCorner = 10.dp,
            passageIndent = 32.dp,
            pageGutter = 20.dp,
            revealDurationMillis = 300,
            revealEasing = defaultRevealEasing,
        ),
    ),
    AppSkin.Tide to SkinSpec(
        light = lightColorScheme(
            primary = Color(0xFF0E6E78),
            onPrimary = Color(0xFFF3FFFE),
            secondary = Color(0xFF3D7A9A),
            background = Color(0xFFE5F4F6),
            surface = Color(0xFFF4FBFB),
            surfaceVariant = Color(0xFFD0E7EA),
        ),
        dark = darkColorScheme(
            primary = Color(0xFF7ED0D6),
            onPrimary = Color(0xFF00363A),
            secondary = Color(0xFF9FCBE0),
            background = Color(0xFF07161A),
            surface = Color(0xFF102328),
            surfaceVariant = Color(0xFF1C343A),
        ),
        shapes = skinShapes(4, 36, 8, 40),
        // 潮汐:海雾白纸配深墨青,强调是浪尖的碧色
        twineLight = TwineTokens(
            paper = Color(0xFFF7FCFD),
            paperEdge = Color(0xFFBFDDE2),
            ink = Color(0xFF12333B),
            inkFaded = Color(0xFF5E8189),
            inkAccent = Color(0xFF0E6E78),
            inkAccentMuted = Color(0xFF0E6E78).copy(alpha = 0.5f),
            highlight = Color(0xFF9FE3EA).copy(alpha = 0.4f),
            passageCorner = 16.dp,
            passageIndent = 32.dp,
            pageGutter = 22.dp,
            revealDurationMillis = 340,
            revealEasing = defaultRevealEasing,
        ),
        twineDark = TwineTokens(
            paper = Color(0xFF0E2026),
            paperEdge = Color(0xFF1E3C44),
            ink = Color(0xFFD6ECEF),
            inkFaded = Color(0xFF7FA3AA),
            inkAccent = Color(0xFF7ED0D6),
            inkAccentMuted = Color(0xFF7ED0D6).copy(alpha = 0.5f),
            highlight = Color(0xFF1E5E66).copy(alpha = 0.5f),
            passageCorner = 16.dp,
            passageIndent = 32.dp,
            pageGutter = 22.dp,
            revealDurationMillis = 340,
            revealEasing = defaultRevealEasing,
        ),
    ),
    AppSkin.Neon to SkinSpec(
        light = lightColorScheme(
            primary = Color(0xFFB0006E),
            onPrimary = Color(0xFFFFF7FB),
            secondary = Color(0xFF006E8C),
            background = Color(0xFFF7F2FF),
            surface = Color(0xFFFFFBFF),
            surfaceVariant = Color(0xFFE7DDF8),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFF4FA3),
            onPrimary = Color(0xFF3D0024),
            secondary = Color(0xFF3DDCFF),
            background = Color(0xFF100818),
            surface = Color(0xFF1A1028),
            surfaceVariant = Color(0xFF2C1840),
        ),
        shapes = skinShapes(16, 0, 16, 0),
        // 霓虹:夜色荧光纸,墨是亮的,强调是霓虹粉 —— 唯一"暗纸亮墨"的皮肤
        twineLight = TwineTokens(
            paper = Color(0xFFFFFBFF),
            paperEdge = Color(0xFFE2D8F0),
            ink = Color(0xFF251532),
            inkFaded = Color(0xFF6E5F80),
            inkAccent = Color(0xFFB0006E),
            inkAccentMuted = Color(0xFFB0006E).copy(alpha = 0.5f),
            highlight = Color(0xFFB0006E).copy(alpha = 0.14f),
            passageCorner = 6.dp,
            passageIndent = 32.dp,
            pageGutter = 18.dp,
            revealDurationMillis = 240,
            revealEasing = defaultRevealEasing,
        ),
        twineDark = TwineTokens(
            paper = Color(0xFF14091E),
            paperEdge = Color(0xFF33194A),
            ink = Color(0xFFEFE2FA),
            inkFaded = Color(0xFF9C87B3),
            inkAccent = Color(0xFFFF4FA3),
            inkAccentMuted = Color(0xFFFF4FA3).copy(alpha = 0.55f),
            highlight = Color(0xFFFF4FA3).copy(alpha = 0.18f),
            passageCorner = 6.dp,
            passageIndent = 32.dp,
            pageGutter = 18.dp,
            revealDurationMillis = 240,
            revealEasing = defaultRevealEasing,
        ),
    ),
)

@Composable
fun VoiceBookTheme(seedState: SeedColorState? = null, content: @Composable () -> Unit) {
    val controller = LocalThemeController.current
    val preference = controller.preference.collectAsStateWithLifecycle().value
    val dark = when (preference.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val skin = skins.getValue(preference.skin)
    val dynamic = when (preference.skin) {
        AppSkin.System -> rememberDynamicColorScheme(dark)
        // 动画后的配色（逐角色 lerp 的中间帧）直接作为 scheme，切换 seed 时整屏平滑过渡。
        AppSkin.Dynamic -> seedState?.let { if (dark) it.animator.darkScheme else it.animator.lightScheme }
        else -> null
    }
    val scheme = dynamic ?: if (dark) skin.dark else skin.light
    // Twine 令牌:皮肤手工调校的优先,否则(System/封面取色)从当前配色派生
    val twine = remember(scheme, dark) {
        (if (dark) skin.twineDark else skin.twineLight)
            ?: TwineTokens.fromScheme(scheme, dark)
    }
    MaterialTheme(colorScheme = scheme, shapes = skin.shapes) {
        CompositionLocalProvider(LocalTwineTokens provides twine) {
            SyncSystemBars(dark)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.primaryContainer.copy(alpha = 0.72f), scheme.background),
                        ),
                    ),
            ) {
                content()
            }
        }
    }
}

@Composable
fun ProvideTheme(controller: ThemeController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemeController provides controller, content = content)
}

val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "亮色"
        ThemeMode.Dark -> "暗色"
    }

val AppSkin.label: String
    get() = when (this) {
        AppSkin.System -> "系统色"
        AppSkin.Dynamic -> "封面取色"
        AppSkin.Fold -> "折纸"
        AppSkin.Tide -> "潮汐"
        AppSkin.Neon -> "霓虹"
    }

internal fun String?.toMode(): ThemeMode =
    runCatching { ThemeMode.valueOf(this ?: "") }.getOrDefault(ThemeMode.System)

internal fun String?.toSkin(): AppSkin =
    runCatching { AppSkin.valueOf(this ?: "") }.getOrDefault(AppSkin.System)
