package us.wangxy.voicebook.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Twine 组件层的自有设计令牌,与 Material [ColorScheme] 并行存在:
 * app 骨架(设置页/导航/对话框)继续走 MaterialTheme,阅读与互动小说相关的
 * 组件一律读这里的令牌,做出与 Material 拉开差距的"纸页 + 墨色"风格。
 *
 * 语义约定:paper 是承载正文的纸面,ink 是落在纸上的字,强调与高亮是
 * 钢笔在纸上的批注 —— 暗色主题下相应反转(纸变深、墨变亮)。
 */
data class TwineTokens(
    // —— 色彩 ——
    /** 纸面:段落卡、书页的底色 */
    val paper: Color,
    /** 纸缘:描边、分隔线,取 paper 与 ink 之间的过渡色 */
    val paperEdge: Color,
    /** 正文墨色 */
    val ink: Color,
    /** 淡墨:次要说明、页码 */
    val inkFaded: Color,
    /** 强调墨:链接、活跃分支,钢笔批注色 */
    val inkAccent: Color,
    /** 淡强调:链接按压、已走过的分支 */
    val inkAccentMuted: Color,
    /** 批注高亮:荧光笔划过的底色 */
    val highlight: Color,
    // —— 形 ——
    /** 段落卡圆角 */
    val passageCorner: Dp,
    /** 段落首行缩进(东亚排版习惯) */
    val passageIndent: Dp,
    /** 书页左右留白 */
    val pageGutter: Dp,
    // —— 动效 ——
    /** 段落显现时长(入场淡入) */
    val revealDurationMillis: Int,
    /** 段落显现曲线:先缓入后明显收尾,模仿纸页被翻开时的阻尼 */
    val revealEasing: Easing,
) {
    companion object {
        /** 从 Material 配色派生一套令牌:给 System/封面取色这类"跟随方案"的皮肤。 */
        fun fromScheme(scheme: ColorScheme, dark: Boolean): TwineTokens = TwineTokens(
            paper = scheme.surface,
            paperEdge = if (dark) Color(0x33FFFFFF) else Color(0x22000000),
            ink = scheme.onSurface,
            inkFaded = scheme.onSurfaceVariant,
            inkAccent = scheme.primary,
            inkAccentMuted = scheme.primary.copy(alpha = 0.55f),
            highlight = scheme.secondaryContainer,
            passageCorner = 14.dp,
            passageIndent = 32.dp,
            pageGutter = 20.dp,
            revealDurationMillis = 280,
            revealEasing = defaultRevealEasing,
        )
    }
}

/** 令牌默认动效参数集中一处,各皮肤覆盖时保持同一节奏基准。 */
internal val defaultRevealEasing: Easing = CubicBezierEasing(0.42f, 0f, 0.16f, 1f)

val LocalTwineTokens = staticCompositionLocalOf<TwineTokens> {
    error("TwineTokens is missing — ensure VoiceBookTheme wraps the content")
}
