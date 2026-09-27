package us.wangxy.voicebook.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 主题氛围色动画器（借鉴 Twine 的 DynamicColorState）：
 * - 目标 seed 生成亮/暗两套 Material 配色（material-kolor），缓存复用；
 * - 帧循环里对整套 ColorScheme 的每个角色做逐通道 lerp，FastOutSlowIn 缓动；
 * - 进度变化 < 2% 时跳过重算（Twine 的节流），避免无意义的重组。
 */
class SeedSchemeAnimator(
    initialLight: ColorScheme = lightColorScheme(),
    initialDark: ColorScheme = darkColorScheme(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    var lightScheme: ColorScheme by mutableStateOf(initialLight)
        private set
    var darkScheme: ColorScheme by mutableStateOf(initialDark)
        private set

    private var currentSeed: Int? = null
    private var animJob: Job? = null

    /**
     * 首次赋值直接跳变（冷启动/进入文章时的初值就是当前氛围），
     * 之后每次 seed 变化都播放 lerp 过渡。
     */
    fun animateTo(seed: Int?) {
        if (seed == null || seed == currentSeed) return
        val first = currentSeed == null
        currentSeed = seed

        val targetLight = dynamicColorScheme(Color(seed), isDark = false, style = PaletteStyle.TonalSpot)
        val targetDark = dynamicColorScheme(Color(seed), isDark = true, style = PaletteStyle.TonalSpot)

        if (first) {
            lightScheme = targetLight
            darkScheme = targetDark
            return
        }

        val fromLight = lightScheme
        val fromDark = darkScheme
        animJob?.cancel()
        animJob = scope.launch {
            var lastFraction = 0f
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = DurationMs, easing = FastOutSlowInEasing),
            ) { value, _ ->
                // Twine 的节流：进度变化不足 2% 不重算整套配色。
                if (abs(value - lastFraction) >= ProgressStep || value == 1f) {
                    lastFraction = value
                    lightScheme = lerpScheme(fromLight, targetLight, value)
                    darkScheme = lerpScheme(fromDark, targetDark, value)
                }
            }
        }
    }

    /** 回到无氛围色状态（离开独立氛围的页面时用）。 */
    fun reset() {
        animJob?.cancel()
        animJob = null
        currentSeed = null
        lightScheme = lightColorScheme()
        darkScheme = darkColorScheme()
    }

    private companion object {
        const val DurationMs = 600
        const val ProgressStep = 0.02f
    }
}

/** 整套 ColorScheme 逐角色 lerp（此版本 material3 未内置）。 */
private fun lerpScheme(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme = from.copy(
    primary = lerp(from.primary, to.primary, fraction),
    onPrimary = lerp(from.onPrimary, to.onPrimary, fraction),
    primaryContainer = lerp(from.primaryContainer, to.primaryContainer, fraction),
    onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, fraction),
    inversePrimary = lerp(from.inversePrimary, to.inversePrimary, fraction),
    secondary = lerp(from.secondary, to.secondary, fraction),
    onSecondary = lerp(from.onSecondary, to.onSecondary, fraction),
    secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, fraction),
    onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, fraction),
    tertiary = lerp(from.tertiary, to.tertiary, fraction),
    onTertiary = lerp(from.onTertiary, to.onTertiary, fraction),
    tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, fraction),
    onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, fraction),
    background = lerp(from.background, to.background, fraction),
    onBackground = lerp(from.onBackground, to.onBackground, fraction),
    surface = lerp(from.surface, to.surface, fraction),
    onSurface = lerp(from.onSurface, to.onSurface, fraction),
    surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, fraction),
    onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, fraction),
    surfaceTint = lerp(from.surfaceTint, to.surfaceTint, fraction),
    inverseSurface = lerp(from.inverseSurface, to.inverseSurface, fraction),
    inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, fraction),
    error = lerp(from.error, to.error, fraction),
    onError = lerp(from.onError, to.onError, fraction),
    errorContainer = lerp(from.errorContainer, to.errorContainer, fraction),
    onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, fraction),
    outline = lerp(from.outline, to.outline, fraction),
    outlineVariant = lerp(from.outlineVariant, to.outlineVariant, fraction),
    scrim = lerp(from.scrim, to.scrim, fraction),
    surfaceBright = lerp(from.surfaceBright, to.surfaceBright, fraction),
    surfaceDim = lerp(from.surfaceDim, to.surfaceDim, fraction),
    surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, fraction),
    surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, fraction),
    surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, fraction),
    surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, fraction),
    surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, fraction),
)
