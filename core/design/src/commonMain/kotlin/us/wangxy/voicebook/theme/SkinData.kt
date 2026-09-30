package us.wangxy.voicebook.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.bloom.BloomShape
import us.wangxy.voicebook.bloom.BloomSmoothing

/**
 * 皮肤的数据定义:内置皮肤(Fold/Tide/Neon)与用户自定义皮肤共用同一模型,
 * JSON 导入/导出走 [SkinDataCodec]。System/Dynamic 不走数据(平台动态色/封面取色)。
 *
 * 颜色一律是 `#AARRGGBB` hex 字符串;[SkinColors] 未指定的角色回落 M3 基线配色,
 * 与旧行为一致(旧 skins map 只覆盖 6 个核心角色,其余走 lightColorScheme() 默认)。
 */
@Immutable
@Serializable
data class SkinData(
    val id: String,
    val name: String,
    val version: Int = 1,
    val light: SkinColors,
    val dark: SkinColors,
    val shape: SkinShape,
    /** 手工调校的 Twine 令牌;null 侧从 ColorScheme 派生。revealEasing 不序列化,统一用 defaultRevealEasing。 */
    val twine: TwineSkinData? = null,
) {
    companion object {
        /**
         * 从 SkinSpec 反推 SkinData(内置迁移 roundtrip / 导出现有皮肤用)。
         * ColorScheme 中与 M3 基线相同的角色记为 null,保证 hex 只来自真实手调值。
         */
        internal fun fromSkinSpec(id: String, name: String, spec: SkinSpec): SkinData {
            val light = spec.light.toSkinColors(lightColorScheme())
            val dark = spec.dark.toSkinColors(darkColorScheme())
            val shape = spec.shapeData()
            return SkinData(
                id = id,
                name = name,
                light = light,
                dark = dark,
                shape = shape,
                twine = if (spec.twineLight == null && spec.twineDark == null) {
                    null
                } else {
                    TwineSkinData(
                        light = spec.twineLight?.toTwineSkinColors(),
                        dark = spec.twineDark?.toTwineSkinColors(),
                    )
                },
            )
        }

        /** 内置三套手调皮肤的数据定义(hex 与旧 skins map 一一对应)。 */
        fun builtinSkinData(): List<SkinData> = builtinSkinDataList()
    }
}

/** ColorScheme 角色的可选覆盖;null = 沿用 M3 基线。 */
@Immutable
@Serializable
data class SkinColors(
    val primary: String? = null,
    val onPrimary: String? = null,
    val primaryContainer: String? = null,
    val onPrimaryContainer: String? = null,
    val inversePrimary: String? = null,
    val secondary: String? = null,
    val onSecondary: String? = null,
    val secondaryContainer: String? = null,
    val onSecondaryContainer: String? = null,
    val tertiary: String? = null,
    val onTertiary: String? = null,
    val tertiaryContainer: String? = null,
    val onTertiaryContainer: String? = null,
    val background: String? = null,
    val onBackground: String? = null,
    val surface: String? = null,
    val onSurface: String? = null,
    val surfaceVariant: String? = null,
    val onSurfaceVariant: String? = null,
    val surfaceTint: String? = null,
    val inverseSurface: String? = null,
    val inverseOnSurface: String? = null,
    val error: String? = null,
    val onError: String? = null,
    val errorContainer: String? = null,
    val onErrorContainer: String? = null,
    val outline: String? = null,
    val outlineVariant: String? = null,
    val scrim: String? = null,
    val surfaceDim: String? = null,
    val surfaceBright: String? = null,
    val surfaceContainer: String? = null,
    val surfaceContainerLow: String? = null,
    val surfaceContainerLowest: String? = null,
    val surfaceContainerHigh: String? = null,
    val surfaceContainerHighest: String? = null,
)

/** 四角圆角(dp) + squircle smoothing。 */
@Immutable
@Serializable
data class SkinShape(
    val topStart: Int,
    val topEnd: Int,
    val bottomEnd: Int,
    val bottomStart: Int,
    val smoothing: Float = BloomSmoothing.Lively,
)

/** Twine 令牌的手调值;颜色 `#AARRGGBB`,几何为 dp 整数。 */
@Immutable
@Serializable
data class TwineSkinData(
    val light: TwineSkinColors? = null,
    val dark: TwineSkinColors? = null,
)

@Immutable
@Serializable
data class TwineSkinColors(
    val paper: String,
    val paperEdge: String,
    val ink: String,
    val inkFaded: String,
    val inkAccent: String,
    @SerialName("inkAccentMuted") val inkAccentMuted: String,
    val highlight: String,
    val passageCorner: Int,
    val passageIndent: Int,
    val pageGutter: Int,
    val revealDurationMillis: Int,
)

// —— 转换:SkinData ⇄ SkinSpec ——

internal fun SkinData.toSkinSpec(): SkinSpec = SkinSpec(
    light = light.toColorScheme(dark = false),
    dark = dark.toColorScheme(dark = true),
    shapes = skinShapes(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart, shape.smoothing),
    smoothing = shape.smoothing,
    twineLight = twine?.light?.toTwineTokens(),
    twineDark = twine?.dark?.toTwineTokens(),
)

private fun SkinColors.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary?.toSkinColor() ?: base.primary,
        onPrimary = onPrimary?.toSkinColor() ?: base.onPrimary,
        primaryContainer = primaryContainer?.toSkinColor() ?: base.primaryContainer,
        onPrimaryContainer = onPrimaryContainer?.toSkinColor() ?: base.onPrimaryContainer,
        inversePrimary = inversePrimary?.toSkinColor() ?: base.inversePrimary,
        secondary = secondary?.toSkinColor() ?: base.secondary,
        onSecondary = onSecondary?.toSkinColor() ?: base.onSecondary,
        secondaryContainer = secondaryContainer?.toSkinColor() ?: base.secondaryContainer,
        onSecondaryContainer = onSecondaryContainer?.toSkinColor() ?: base.onSecondaryContainer,
        tertiary = tertiary?.toSkinColor() ?: base.tertiary,
        onTertiary = onTertiary?.toSkinColor() ?: base.onTertiary,
        tertiaryContainer = tertiaryContainer?.toSkinColor() ?: base.tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer?.toSkinColor() ?: base.onTertiaryContainer,
        background = background?.toSkinColor() ?: base.background,
        onBackground = onBackground?.toSkinColor() ?: base.onBackground,
        surface = surface?.toSkinColor() ?: base.surface,
        onSurface = onSurface?.toSkinColor() ?: base.onSurface,
        surfaceVariant = surfaceVariant?.toSkinColor() ?: base.surfaceVariant,
        onSurfaceVariant = onSurfaceVariant?.toSkinColor() ?: base.onSurfaceVariant,
        surfaceTint = surfaceTint?.toSkinColor() ?: base.surfaceTint,
        inverseSurface = inverseSurface?.toSkinColor() ?: base.inverseSurface,
        inverseOnSurface = inverseOnSurface?.toSkinColor() ?: base.inverseOnSurface,
        error = error?.toSkinColor() ?: base.error,
        onError = onError?.toSkinColor() ?: base.onError,
        errorContainer = errorContainer?.toSkinColor() ?: base.errorContainer,
        onErrorContainer = onErrorContainer?.toSkinColor() ?: base.onErrorContainer,
        outline = outline?.toSkinColor() ?: base.outline,
        outlineVariant = outlineVariant?.toSkinColor() ?: base.outlineVariant,
        scrim = scrim?.toSkinColor() ?: base.scrim,
        surfaceDim = surfaceDim?.toSkinColor() ?: base.surfaceDim,
        surfaceBright = surfaceBright?.toSkinColor() ?: base.surfaceBright,
        surfaceContainer = surfaceContainer?.toSkinColor() ?: base.surfaceContainer,
        surfaceContainerLow = surfaceContainerLow?.toSkinColor() ?: base.surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest?.toSkinColor() ?: base.surfaceContainerLowest,
        surfaceContainerHigh = surfaceContainerHigh?.toSkinColor() ?: base.surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest?.toSkinColor() ?: base.surfaceContainerHighest,
    )
}

private fun TwineSkinColors.toTwineTokens(): TwineTokens = TwineTokens(
    paper = paper.toSkinColor(),
    paperEdge = paperEdge.toSkinColor(),
    ink = ink.toSkinColor(),
    inkFaded = inkFaded.toSkinColor(),
    inkAccent = inkAccent.toSkinColor(),
    inkAccentMuted = inkAccentMuted.toSkinColor(),
    highlight = highlight.toSkinColor(),
    passageCorner = passageCorner.dp,
    passageIndent = passageIndent.dp,
    pageGutter = pageGutter.dp,
    revealDurationMillis = revealDurationMillis,
    // easing 动效曲线不可序列化;现所有皮肤都是同一节奏,统一回落默认值
    revealEasing = defaultRevealEasing,
)

/**
 * 从 SkinSpec 反推 SkinData(内置迁移 roundtrip / 导出现有皮肤用)。
 * ColorScheme 中与 M3 基线相同的角色记为 null,保证 hex 只来自真实手调值。
 */
private fun ColorScheme.toSkinColors(baseline: ColorScheme): SkinColors = SkinColors(
    primary = primary.skinHexOrNull(baseline.primary),
    onPrimary = onPrimary.skinHexOrNull(baseline.onPrimary),
    primaryContainer = primaryContainer.skinHexOrNull(baseline.primaryContainer),
    onPrimaryContainer = onPrimaryContainer.skinHexOrNull(baseline.onPrimaryContainer),
    inversePrimary = inversePrimary.skinHexOrNull(baseline.inversePrimary),
    secondary = secondary.skinHexOrNull(baseline.secondary),
    onSecondary = onSecondary.skinHexOrNull(baseline.onSecondary),
    secondaryContainer = secondaryContainer.skinHexOrNull(baseline.secondaryContainer),
    onSecondaryContainer = onSecondaryContainer.skinHexOrNull(baseline.onSecondaryContainer),
    tertiary = tertiary.skinHexOrNull(baseline.tertiary),
    onTertiary = onTertiary.skinHexOrNull(baseline.onTertiary),
    tertiaryContainer = tertiaryContainer.skinHexOrNull(baseline.tertiaryContainer),
    onTertiaryContainer = onTertiaryContainer.skinHexOrNull(baseline.onTertiaryContainer),
    background = background.skinHexOrNull(baseline.background),
    onBackground = onBackground.skinHexOrNull(baseline.onBackground),
    surface = surface.skinHexOrNull(baseline.surface),
    onSurface = onSurface.skinHexOrNull(baseline.onSurface),
    surfaceVariant = surfaceVariant.skinHexOrNull(baseline.surfaceVariant),
    onSurfaceVariant = onSurfaceVariant.skinHexOrNull(baseline.onSurfaceVariant),
    surfaceTint = surfaceTint.skinHexOrNull(baseline.surfaceTint),
    inverseSurface = inverseSurface.skinHexOrNull(baseline.inverseSurface),
    inverseOnSurface = inverseOnSurface.skinHexOrNull(baseline.inverseOnSurface),
    error = error.skinHexOrNull(baseline.error),
    onError = onError.skinHexOrNull(baseline.onError),
    errorContainer = errorContainer.skinHexOrNull(baseline.errorContainer),
    onErrorContainer = onErrorContainer.skinHexOrNull(baseline.onErrorContainer),
    outline = outline.skinHexOrNull(baseline.outline),
    outlineVariant = outlineVariant.skinHexOrNull(baseline.outlineVariant),
    scrim = scrim.skinHexOrNull(baseline.scrim),
    surfaceDim = surfaceDim.skinHexOrNull(baseline.surfaceDim),
    surfaceBright = surfaceBright.skinHexOrNull(baseline.surfaceBright),
    surfaceContainer = surfaceContainer.skinHexOrNull(baseline.surfaceContainer),
    surfaceContainerLow = surfaceContainerLow.skinHexOrNull(baseline.surfaceContainerLow),
    surfaceContainerLowest = surfaceContainerLowest.skinHexOrNull(baseline.surfaceContainerLowest),
    surfaceContainerHigh = surfaceContainerHigh.skinHexOrNull(baseline.surfaceContainerHigh),
    surfaceContainerHighest = surfaceContainerHighest.skinHexOrNull(baseline.surfaceContainerHighest),
)

private fun Color.skinHexOrNull(baseline: Color): String? = if (this == baseline) null else toSkinHex()

private fun TwineTokens.toTwineSkinColors(): TwineSkinColors = TwineSkinColors(
    paper = paper.toSkinHex(),
    paperEdge = paperEdge.toSkinHex(),
    ink = ink.toSkinHex(),
    inkFaded = inkFaded.toSkinHex(),
    inkAccent = inkAccent.toSkinHex(),
    inkAccentMuted = inkAccentMuted.toSkinHex(),
    highlight = highlight.toSkinHex(),
    passageCorner = passageCorner.value.toInt(),
    passageIndent = passageIndent.value.toInt(),
    pageGutter = pageGutter.value.toInt(),
    revealDurationMillis = revealDurationMillis,
)

/** skinShapes() 的 medium 档保存了四角原始 dp;用单位 Density 从 CornerSize 反推。 */
private fun SkinSpec.shapeData(): SkinShape {
    val medium = shapes.medium as? BloomShape
    requireNotNull(medium) { "皮肤 Shapes 必须是 BloomShape" }
    return SkinShape(
        topStart = medium.topStart.skinCornerDp(),
        topEnd = medium.topEnd.skinCornerDp(),
        bottomEnd = medium.bottomEnd.skinCornerDp(),
        bottomStart = medium.bottomStart.skinCornerDp(),
        smoothing = medium.smoothing,
    )
}

private val unitDensity = Density(1f)
private val unitSize = Size(1f, 1f)

private fun androidx.compose.foundation.shape.CornerSize.skinCornerDp(): Int =
    toPx(unitSize, unitDensity).toInt()

// —— hex 编解码 ——

/** `#AARRGGBB` 大写 hex;alpha 走 toArgb 的就近取整,往返稳定。 */
internal fun Color.toSkinHex(): String = "#" + toArgb().toUInt().toString(16).padStart(8, '0').uppercase()

/** 接受 `#AARRGGBB` / `#RRGGBB`(补全 FF alpha),也容忍 `0x` 前缀与小写。 */
internal fun String.toSkinColor(): Color {
    val raw = trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    val argb = when (raw.length) {
        6 -> 0xFF000000L or (raw.toLong(16))
        8 -> raw.toLong(16)
        else -> throw IllegalArgumentException("非法颜色值: $this")
    }
    return Color(argb.toInt())
}

// —— JSON 编解码 ——

/** 皮肤 JSON 的统一编解码入口;导入侧容忍未知字段(向前兼容)。 */
internal object SkinDataCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(SkinData.serializer())

    fun encode(skin: SkinData): String = json.encodeToString(SkinData.serializer(), skin)

    fun decode(text: String): SkinData = json.decodeFromString(SkinData.serializer(), text)

    fun encodeList(skins: List<SkinData>): String = json.encodeToString(listSerializer, skins)

    fun decodeList(text: String): List<SkinData> = json.decodeFromString(listSerializer, text)
}

private fun builtinSkinDataList(): List<SkinData> = listOf(
    SkinData(
        id = "fold",
        name = "折纸",
        light = SkinColors(
            primary = "#FF6B3A2A",
            onPrimary = "#FFFFF8F0",
            secondary = "#FF8A5A44",
            background = "#FFF6E7D4",
            surface = "#FFFFF3E4",
            surfaceVariant = "#FFE7D3BC",
        ),
        dark = SkinColors(
            primary = "#FFE7B089",
            onPrimary = "#FF3A2418",
            secondary = "#FFD7A07A",
            background = "#FF1C1410",
            surface = "#FF2A1E18",
            surfaceVariant = "#FF3D2C24",
        ),
        shape = SkinShape(topStart = 28, topEnd = 4, bottomEnd = 22, bottomStart = 6, smoothing = BloomSmoothing.Lively),
        twine = TwineSkinData(
            light = TwineSkinColors(
                paper = "#FFFFF8EC",
                paperEdge = "#FFD8C4A5",
                ink = "#FF3A2C1E",
                inkFaded = "#FF8C7B66",
                inkAccent = "#FF9C3B22",
                inkAccentMuted = "#809C3B22",
                highlight = "#73F5D76E",
                passageCorner = 10,
                passageIndent = 32,
                pageGutter = 20,
                revealDurationMillis = 300,
            ),
            dark = TwineSkinColors(
                paper = "#FF241A14",
                paperEdge = "#FF4A382B",
                ink = "#FFE8D8C2",
                inkFaded = "#FFA08D75",
                inkAccent = "#FFE2814F",
                inkAccentMuted = "#80E2814F",
                highlight = "#738A6A1F",
                passageCorner = 10,
                passageIndent = 32,
                pageGutter = 20,
                revealDurationMillis = 300,
            ),
        ),
    ),
    SkinData(
        id = "tide",
        name = "潮汐",
        light = SkinColors(
            primary = "#FF0E6E78",
            onPrimary = "#FFF3FFFE",
            secondary = "#FF3D7A9A",
            background = "#FFE5F4F6",
            surface = "#FFF4FBFB",
            surfaceVariant = "#FFD0E7EA",
        ),
        dark = SkinColors(
            primary = "#FF7ED0D6",
            onPrimary = "#FF00363A",
            secondary = "#FF9FCBE0",
            background = "#FF07161A",
            surface = "#FF102328",
            surfaceVariant = "#FF1C343A",
        ),
        shape = SkinShape(topStart = 4, topEnd = 36, bottomEnd = 8, bottomStart = 40, smoothing = BloomSmoothing.Pillowy),
        twine = TwineSkinData(
            light = TwineSkinColors(
                paper = "#FFF7FCFD",
                paperEdge = "#FFBFDDE2",
                ink = "#FF12333B",
                inkFaded = "#FF5E8189",
                inkAccent = "#FF0E6E78",
                inkAccentMuted = "#800E6E78",
                highlight = "#669FE3EA",
                passageCorner = 16,
                passageIndent = 32,
                pageGutter = 22,
                revealDurationMillis = 340,
            ),
            dark = TwineSkinColors(
                paper = "#FF0E2026",
                paperEdge = "#FF1E3C44",
                ink = "#FFD6ECEF",
                inkFaded = "#FF7FA3AA",
                inkAccent = "#FF7ED0D6",
                inkAccentMuted = "#807ED0D6",
                highlight = "#801E5E66",
                passageCorner = 16,
                passageIndent = 32,
                pageGutter = 22,
                revealDurationMillis = 340,
            ),
        ),
    ),
    SkinData(
        id = "neon",
        name = "霓虹",
        light = SkinColors(
            primary = "#FFB0006E",
            onPrimary = "#FFFFF7FB",
            secondary = "#FF006E8C",
            background = "#FFF7F2FF",
            surface = "#FFFFFBFF",
            surfaceVariant = "#FFE7DDF8",
        ),
        dark = SkinColors(
            primary = "#FFFF4FA3",
            onPrimary = "#FF3D0024",
            secondary = "#FF3DDCFF",
            background = "#FF100818",
            surface = "#FF1A1028",
            surfaceVariant = "#FF2C1840",
        ),
        shape = SkinShape(topStart = 16, topEnd = 0, bottomEnd = 16, bottomStart = 0, smoothing = 0.78f),
        twine = TwineSkinData(
            light = TwineSkinColors(
                paper = "#FFFFFBFF",
                paperEdge = "#FFE2D8F0",
                ink = "#FF251532",
                inkFaded = "#FF6E5F80",
                inkAccent = "#FFB0006E",
                inkAccentMuted = "#80B0006E",
                highlight = "#24B0006E",
                passageCorner = 6,
                passageIndent = 32,
                pageGutter = 18,
                revealDurationMillis = 240,
            ),
            dark = TwineSkinColors(
                paper = "#FF14091E",
                paperEdge = "#FF33194A",
                ink = "#FFEFE2FA",
                inkFaded = "#FF9C87B3",
                inkAccent = "#FFFF4FA3",
                inkAccentMuted = "#8CFF4FA3",
                highlight = "#2EFF4FA3",
                passageCorner = 6,
                passageIndent = 32,
                pageGutter = 18,
                revealDurationMillis = 240,
            ),
        ),
    ),
)
