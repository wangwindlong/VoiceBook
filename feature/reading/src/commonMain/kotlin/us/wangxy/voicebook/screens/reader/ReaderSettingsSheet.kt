package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.reader.store.PdfPageFit
import us.wangxy.voicebook.reader.store.ReaderFontFamily
import us.wangxy.voicebook.reader.store.ReaderPageTurn
import us.wangxy.voicebook.theme.ThemeBar
import us.wangxy.voicebook.twine.TwineSheet
import us.wangxy.voicebook.ui.widget.LabeledSwitch
import us.wangxy.voicebook.ui.widget.OptionChipRow
import us.wangxy.voicebook.ui.widget.TitledSection

/**
 * 阅读设置面板(TwineSheet 承载)。EPUB/TXT 显示全部排版与阅读体验项;
 * PDF 为固定版式,排版项(字号/行距/边距/字体/缩进)不出现,保留页面适配与体验项。
 */
@Composable
internal fun ReaderSettingsSheet(
    pdfMode: Boolean,
    pdfPageFit: PdfPageFit,
    onPdfPageFit: (PdfPageFit) -> Unit,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    lineHeight: Float,
    onLineHeight: (Float) -> Unit,
    marginDp: Int,
    onMargin: (Int) -> Unit,
    fontFamily: ReaderFontFamily,
    onFontFamily: (ReaderFontFamily) -> Unit,
    firstLineIndent: Boolean,
    onFirstLineIndent: (Boolean) -> Unit,
    brightness: Float,
    onBrightness: (Float) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    autoReadMillis: Long,
    onAutoRead: (Long) -> Unit,
    pageTurn: ReaderPageTurn,
    onPageTurn: (ReaderPageTurn) -> Unit,
    onDismiss: () -> Unit,
) {
    TwineSheet(
        visible = true,
        onDismiss = onDismiss,
        peekFraction = 0.72f,
    ) {
        Text(
            "阅读设置",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (pdfMode) {
            // PDF 为整页位图,排版项不可用;明示原因,避免误以为缺功能
            Text(
                "PDF 为固定版式,不支持字号/行距/边距/字体调整;EPUB/TXT 书籍支持完整排版设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TitledSection("主题") { ThemeBar() }
            if (pdfMode) {
                TitledSection("页面适配") {
                    OptionChipRow(
                        listOf(PdfPageFit.FitPage to "适屏", PdfPageFit.FitWidth to "适宽"),
                        pdfPageFit,
                        onPdfPageFit,
                    )
                }
            }
            if (!pdfMode) {
                TitledSection("字号") {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = { moveFont(fontSize, -1, onFontSize) },
                            enabled = fontSize > ReaderFontSizes.first(),
                        ) { Text("A-", style = MaterialTheme.typography.titleMedium) }
                        Text(
                            "${fontSize}pt",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = { moveFont(fontSize, +1, onFontSize) },
                            enabled = fontSize < ReaderFontSizes.last(),
                        ) { Text("A+", style = MaterialTheme.typography.titleLarge) }
                    }
                }
                TitledSection("行距") { OptionChipRow(ReaderLineHeights, lineHeight, onLineHeight) }
                TitledSection("边距") { OptionChipRow(ReaderMargins, marginDp, onMargin) }
                TitledSection("字体") { OptionChipRow(ReaderFontFamilies, fontFamily, onFontFamily) }
                TitledSection("缩进") {
                    OptionChipRow(listOf(true to "缩进", false to "不缩进"), firstLineIndent, onFirstLineIndent)
                }
            }
            if (SupportsBrightness) {
                TitledSection("亮度") {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = brightness < 0f,
                            onClick = { onBrightness(-1f) },
                            label = { Text("跟随系统") },
                        )
                        Slider(
                            value = if (brightness < 0f) 1f else brightness.coerceIn(0.05f, 1f),
                            onValueChange = { onBrightness(it) },
                            enabled = brightness >= 0f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                LabeledSwitch("屏幕常亮", keepScreenOn, onKeepScreenOn)
            }
            TitledSection("自动阅读") { OptionChipRow(ReaderAutoReads, autoReadMillis, onAutoRead) }
            if (SupportsCurlPageTurn) {
                TitledSection("翻页方式") {
                    OptionChipRow(
                        listOf(ReaderPageTurn.Curl to "卷页", ReaderPageTurn.Slide to "平移"),
                        pageTurn,
                        onPageTurn,
                    )
                }
            }
        }
    }
}

