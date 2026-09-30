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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.wangxy.voicebook.bloom.BloomShape
import us.wangxy.voicebook.bloom.BloomSmoothing
import us.wangxy.voicebook.bloom.BloomTokens
import us.wangxy.voicebook.bloom.LocalBloomTokens

enum class ThemeMode { System, Light, Dark }

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.System,
    val skin: String = "system",
)

interface ThemeStore {
    fun load(): ThemePreference
    fun save(preference: ThemePreference)
    fun loadCustomSkins(): List<SkinData>
    fun saveCustomSkins(skins: List<SkinData>)
}

expect fun createThemeStore(): ThemeStore

@Composable
expect fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
expect fun SyncSystemBars(darkTheme: Boolean)

class ThemeController(private val store: ThemeStore) {
    private val preferenceState = MutableStateFlow(store.load())
    val preference: StateFlow<ThemePreference> = preferenceState.asStateFlow()
    private val customSkinsState = MutableStateFlow(store.loadCustomSkins())
    val customSkins: StateFlow<List<SkinData>> = customSkinsState.asStateFlow()

    fun setMode(mode: ThemeMode) = update(preferenceState.value.copy(mode = mode))

    fun setSkin(skinId: String) = update(preferenceState.value.copy(skin = skinId))

    fun importSkin(json: String): Result<SkinData> = runCatching {
        val decoded = SkinDataCodec.decode(json)
        val id = decoded.id.trim()
        if (id.isEmpty()) throw IllegalArgumentException("皮肤 id 不能为空")
        if (BuiltinSkinId.isReserved(id)) {
            throw IllegalArgumentException("不能覆盖内置皮肤: $id")
        }
        val skinData = decoded.copy(id = id)
        val currentCustom = customSkinsState.value
        val updated = currentCustom.filter { it.id != skinData.id } + skinData
        customSkinsState.value = updated
        store.saveCustomSkins(updated)
        skinData
    }

    fun exportSkin(id: String): String? {
        val allSkins = skins.keys.toSet() + customSkinsState.value.map { it.id }.toSet()
        if (!allSkins.contains(id)) return null
        val skinData = customSkinsState.value.firstOrNull { it.id == id }
            ?: SkinData.fromSkinSpec(id, getSkinLabel(id), skins[id]!!)
        return SkinDataCodec.encode(skinData)
    }

    fun removeCustomSkin(id: String) {
        if (BuiltinSkinId.isReserved(id)) return
        val currentCustom = customSkinsState.value
        val updated = currentCustom.filter { it.id != id }
        if (updated.size != currentCustom.size) {
            customSkinsState.value = updated
            store.saveCustomSkins(updated)
            if (preferenceState.value.skin == id) {
                setSkin(BuiltinSkinId.System)
            }
        }
    }

    private fun update(value: ThemePreference) {
        preferenceState.value = value
        store.save(value)
    }

    private fun getSkinLabel(id: String): String =
        customSkinsState.value.firstOrNull { it.id == id }?.name ?: BuiltinSkinId.label(id)
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController is missing")
}

internal data class SkinSpec(
    val light: ColorScheme,
    val dark: ColorScheme,
    val shapes: Shapes,
    val smoothing: Float = BloomSmoothing.Lively,
    /** 手工调校的 Twine 令牌;为 null 时从 ColorScheme 派生(System/Dynamic)。 */
    val twineLight: TwineTokens? = null,
    val twineDark: TwineTokens? = null,
)

internal fun corners(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int, smoothing: Float) =
    BloomShape(topStart.dp, topEnd.dp, bottomEnd.dp, bottomStart.dp, smoothing)

internal fun skinShapes(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int, smoothing: Float) = Shapes(
    extraSmall = corners(topStart / 3, topEnd / 3, bottomEnd / 3, bottomStart / 3, smoothing),
    small = corners(topStart / 2, topEnd / 2, bottomEnd / 2, bottomStart / 2, smoothing),
    medium = corners(topStart, topEnd, bottomEnd, bottomStart, smoothing),
    large = corners(topStart + 8, topEnd + 4, bottomEnd + 10, bottomStart + 2, smoothing),
    extraLarge = corners(topStart + 16, topEnd + 6, bottomEnd + 18, bottomStart + 4, smoothing),
)

/** 内置皮肤 ID。System/Dynamic 不走 [SkinData]，其余三套由数据生成。 */
internal object BuiltinSkinId {
    const val System = "system"
    const val Dynamic = "dynamic"
    const val Fold = "fold"
    const val Tide = "tide"
    const val Neon = "neon"

    val all = listOf(System, Dynamic, Fold, Tide, Neon)

    fun isReserved(id: String): Boolean = id.trim().lowercase() in all

    fun label(id: String): String = when (id) {
        System -> "系统色"
        Dynamic -> "封面取色"
        Fold -> "折纸"
        Tide -> "潮汐"
        Neon -> "霓虹"
        else -> id
    }
}

/** System/Dynamic 特殊皮肤的硬编码 SkinSpec(不走 SkinData)。 */
private val specialSkins: Map<String, SkinSpec> = mapOf(
    BuiltinSkinId.System to SkinSpec(
        light = lightColorScheme(),
        dark = darkColorScheme(),
        shapes = skinShapes(14, 14, 14, 14, BloomSmoothing.Lively),
        smoothing = BloomSmoothing.Lively,
    ),
    // Dynamic generates both schemes from the current book cover's seed color;
    // the static entries here are only the fallback when no seed is available.
    BuiltinSkinId.Dynamic to SkinSpec(
        light = lightColorScheme(),
        dark = darkColorScheme(),
        shapes = skinShapes(16, 16, 16, 16, BloomSmoothing.Lively),
        smoothing = BloomSmoothing.Lively,
    ),
)

/** 所有皮肤的 SkinSpec 表:内置三套由 SkinData 生成,System/Dynamic 保留硬编码。 */
internal val skins: Map<String, SkinSpec> =
    specialSkins + SkinData.builtinSkinData().associate { it.id to it.toSkinSpec() }

@Composable
fun VoiceBookTheme(seedState: SeedColorState? = null, content: @Composable () -> Unit) {
    val controller = LocalThemeController.current
    val preference = controller.preference.collectAsStateWithLifecycle().value
    val customSkins = controller.customSkins.collectAsStateWithLifecycle().value
    val dark = when (preference.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val skinId = preference.skin
    val skin = (skins + customSkins.associate { it.id to it.toSkinSpec() }).getOrElse(skinId) { skins["system"]!! }
    val dynamic = when (skinId) {
        BuiltinSkinId.System -> rememberDynamicColorScheme(dark)
        BuiltinSkinId.Dynamic -> seedState?.let { if (dark) it.animator.darkScheme else it.animator.lightScheme }
        else -> null
    }
    val scheme = dynamic ?: if (dark) skin.dark else skin.light
    // Twine 令牌:皮肤手工调校的优先,否则(System/封面取色)从当前配色派生
    val twine = remember(scheme, dark) {
        (if (dark) skin.twineDark else skin.twineLight)
            ?: TwineTokens.fromScheme(scheme, dark)
    }
    val bloom = remember(scheme, skinId, dark, skin.smoothing) {
        BloomTokens.fromScheme(scheme, skinId, dark, skin.smoothing)
    }
    MaterialTheme(colorScheme = scheme, shapes = skin.shapes) {
        CompositionLocalProvider(
            LocalTwineTokens provides twine,
            LocalBloomTokens provides bloom,
        ) {
            SyncSystemBars(dark)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.primaryContainer.copy(alpha = 0.72f), scheme.background),
                        ),
                    )
                    .drawBehind {
                        val glowAlpha = bloom.glowIntensity
                        val topRadius = size.minDimension * 0.72f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    bloom.glow.copy(alpha = 0.24f * glowAlpha),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.88f, size.height * 0.05f),
                                radius = topRadius,
                            ),
                            radius = topRadius,
                            center = Offset(size.width * 0.88f, size.height * 0.05f),
                        )
                        val bottomRadius = size.minDimension * 0.58f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    bloom.glowSecondary.copy(alpha = 0.18f * glowAlpha),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.08f, size.height * 0.82f),
                                radius = bottomRadius,
                            ),
                            radius = bottomRadius,
                            center = Offset(size.width * 0.08f, size.height * 0.82f),
                        )
                    },
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

internal fun String?.toMode(): ThemeMode =
    runCatching { ThemeMode.valueOf(this ?: "") }.getOrDefault(ThemeMode.System)

/**
 * 持久化皮肤 id 归一。旧版本写入的是枚举名（`System` / `Fold` …），
 * 现在统一用小写 id；未知 id（自定义皮肤）原样保留。
 */
internal fun String?.toSkinId(): String {
    return when (val raw = this?.trim().orEmpty()) {
        "", "System" -> BuiltinSkinId.System
        "Dynamic" -> BuiltinSkinId.Dynamic
        "Fold" -> BuiltinSkinId.Fold
        "Tide" -> BuiltinSkinId.Tide
        "Neon" -> BuiltinSkinId.Neon
        else -> raw
    }
}
