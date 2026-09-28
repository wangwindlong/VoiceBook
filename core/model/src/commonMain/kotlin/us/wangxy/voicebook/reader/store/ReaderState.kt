package us.wangxy.voicebook.reader.store

import kotlinx.serialization.Serializable
import us.wangxy.voicebook.reader.api.CalibreServer

/** 阅读翻页方式:卷页(Android pagecurl)或平移滑动。 */
@Serializable
enum class ReaderPageTurn { Curl, Slide }

/** 正文字体族,映射到 Compose FontFamily。 */
@Serializable
enum class ReaderFontFamily { Default, Serif, Sans, Mono }

/** PDF 整页位图在视口内的适配方式:整页适屏或宽度撑满(垂直可拖动)。 */
@Serializable
enum class PdfPageFit { FitPage, FitWidth }

@Serializable
data class ReaderState(
    val server: CalibreServer? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** Reader body font size in sp. */
    val fontSizeSp: Int = 18,
    /** 行距系数:行高 = 字号 × 该系数。 */
    val lineHeightFactor: Float = 1.65f,
    /** 正文左右边距,dp。 */
    val pageMarginDp: Int = 24,
    /** 翻页方式,默认卷页(仅 Android 支持,其余端忽略为平移)。 */
    val pageTurn: ReaderPageTurn = ReaderPageTurn.Curl,
    /** 正文字体族。 */
    val fontFamily: ReaderFontFamily = ReaderFontFamily.Default,
    /** 首行缩进开关(仅对标记为可缩进的段落生效)。 */
    val firstLineIndent: Boolean = true,
    /** 屏幕亮度,0.05..1;-1 = 跟随系统。 */
    val brightness: Float = -1f,
    /** 阅读时保持屏幕常亮。 */
    val keepScreenOn: Boolean = false,
    /** 自动阅读翻页间隔毫秒;0 = 关闭。 */
    val autoReadMillis: Long = 0,
    /** PDF 页面适配方式(仅 PDF 生效)。 */
    val pdfPageFit: PdfPageFit = PdfPageFit.FitPage,
)
