package us.wangxy.voicebook.screens.reader

import androidx.compose.ui.text.font.FontFamily
import us.wangxy.voicebook.reader.store.ReaderFontFamily

internal val ReaderFontSizes = listOf(14, 16, 18, 20, 24)

/** 行距档位(行距系数,标签)。 */
internal val ReaderLineHeights = listOf(
    1.4f to "紧凑", 1.65f to "标准", 1.9f to "宽松", 2.2f to "特宽",
)

/** 边距档位(左右边距 dp,标签)。 */
internal val ReaderMargins = listOf(16 to "窄", 24 to "标准", 36 to "宽")

/** 自动阅读档位(翻页间隔毫秒,标签)。 */
internal val ReaderAutoReads = listOf(
    0L to "关", 15_000L to "慢", 10_000L to "中", 6_000L to "快",
)

/** 字体族档位。 */
internal val ReaderFontFamilies = listOf(
    ReaderFontFamily.Default to "系统默认",
    ReaderFontFamily.Serif to "衬线",
    ReaderFontFamily.Sans to "无衬线",
    ReaderFontFamily.Mono to "等宽",
)

internal fun mapReaderFontFamily(value: ReaderFontFamily): FontFamily = when (value) {
    ReaderFontFamily.Default -> FontFamily.Default
    ReaderFontFamily.Serif -> FontFamily.Serif
    ReaderFontFamily.Sans -> FontFamily.SansSerif
    ReaderFontFamily.Mono -> FontFamily.Monospace
}

internal inline fun moveFont(current: Int, direction: Int, set: (Int) -> Unit) {
    val at = ReaderFontSizes.indexOf(current).let { if (it < 0) 0 else it }
    set(ReaderFontSizes[(at + direction).coerceIn(0, ReaderFontSizes.size - 1)])
}
