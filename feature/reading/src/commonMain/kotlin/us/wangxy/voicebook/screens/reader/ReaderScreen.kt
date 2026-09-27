package us.wangxy.voicebook.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.VoiceBookTheme
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.paginate.LineBox
import us.wangxy.voicebook.reader.paginate.LineMeasurer
import us.wangxy.voicebook.reader.paginate.PageEntry
import us.wangxy.voicebook.reader.paginate.PageLayout
import us.wangxy.voicebook.reader.paginate.Paginator
import us.wangxy.voicebook.twine.TwineSheet
import us.wangxy.voicebook.reader.pdf.PdfDocument
import us.wangxy.voicebook.reader.render.ParagraphLayout
import us.wangxy.voicebook.reader.render.ReaderStyle
import us.wangxy.voicebook.reader.store.ReaderStateController
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val ReaderFontSizes = listOf(14, 16, 18, 20, 24)

/**
 * 阅读器：下载 EPUB → 按视口分页 → HorizontalPager 翻页。位置以 (章节, 字符偏移)
 * 锚点持久化，重进后从上次位置恢复；字号调整时用锚点重排并回到当前段落。
 */
@Composable
fun ReaderScreen(
    bookId: Int,
    title: String,
    author: String,
    coverUrl: String,
    downloadHref: String = "",
    navigateBack: () -> Unit,
) {
    val viewModel = koinViewModel<ReaderViewModel>()
    val stateController = koinInject<ReaderStateController>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 阅读页独立氛围色（书封 seed）：嵌套主题只作用于本页，纸面随书变色。
    val ambient = remember { SeedColorState() }
    val bookSeed by viewModel.bookSeedColor.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.downloadAndOpen(bookId, title, author, coverUrl, downloadHref)
    }

    LaunchedEffect(bookSeed) { ambient.update(bookSeed) }

    VoiceBookTheme(seedState = ambient) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (val s = state) {
            ReaderUiState.Idle -> Unit

            is ReaderUiState.Downloading -> Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("正在下载《$title》")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            is ReaderUiState.Failed -> Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("打开失败：${s.message}", textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = viewModel::retry) { Text("重试") }
                    TextButton(onClick = navigateBack) { Text("返回") }
                }
            }

            is ReaderUiState.Ready -> BookPager(
                book = s.book,
                startAnchor = viewModel.savedAnchor,
                stateController = stateController,
                viewModel = viewModel,
                bookId = bookId,
                title = title,
                navigateBack = navigateBack,
            )

            is ReaderUiState.ReadyPdf -> PdfPager(
                pdf = s.pdf,
                startPage = viewModel.savedAnchor?.first ?: 0,
                viewModel = viewModel,
                title = title,
                navigateBack = navigateBack,
            )
        }
    }
    }
}

@Composable
private fun BookPager(
    book: ReadableBook,
    startAnchor: Pair<Int, Int>?,
    stateController: ReaderStateController,
    viewModel: ReaderViewModel,
    bookId: Int,
    title: String,
    navigateBack: () -> Unit,
) {
    var fontSize by remember { mutableIntStateOf(stateController.state.value.fontSizeSp) }
    var chapterIndex by remember {
        mutableIntStateOf(startAnchor?.first?.coerceIn(0, book.chapters.size - 1) ?: 0)
    }
    var showChrome by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    LaunchedEffect(fontSize) { stateController.setFontSize(fontSize) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val style = ReaderStyle(
            fontSizeSp = fontSize.toFloat(),
            horizontalPaddingPx = with(density) { 24.dp.toPx() },
            topPaddingPx = with(density) { 20.dp.toPx() },
            bottomPaddingPx = with(density) { 28.dp.toPx() },
        )
        val contentWidth = widthPx - style.horizontalPaddingPx * 2
        val contentHeight = heightPx - style.topPaddingPx - style.bottomPaddingPx

        val blocks = remember(chapterIndex) { book.chapterBlocks(chapterIndex) }
        val lineMeasurer = rememberBookLineMeasurer(style, contentWidth)
        val pages = remember(chapterIndex, fontSize, widthPx, heightPx) {
            Paginator.paginate(
                blocks = blocks,
                measurer = lineMeasurer,
                style = style,
                layout = PageLayout(contentWidth, contentHeight),
            )
        }

        val targetOffset = remember(chapterIndex) { startAnchor?.second ?: 0 }
        // Twine 卷页(Android)/HorizontalPager(其他端)由 ReaderPagerSurface 抽象承载
        var settledPage by remember { mutableIntStateOf(0) }
        val jumpToPage = if (targetOffset > 0) Paginator.pageForOffset(pages, targetOffset) else 0

        // Persist the position whenever the page settles. Re-collecting on chapter/font
        // change re-emits the settled page once — the save is idempotent.
        LaunchedEffect(settledPage, pages, chapterIndex) {
            val anchor = pages.getOrNull(settledPage)?.anchorOffset ?: 0
            val percent = chapterIndex * 100 / book.chapters.size.coerceAtLeast(1)
            viewModel.recordPosition(chapterIndex, anchor, percent)
        }

        ReaderPagerSurface(
            pageCount = pages.size,
            initialPage = 0,
            jumpToPage = jumpToPage,
            modifier = Modifier.fillMaxSize(),
            onSettledPage = { settledPage = it },
            onLeftEdgeTap = { if (chapterIndex > 0) chapterIndex-- },
            onRightEdgeTap = { if (chapterIndex < book.chapters.size - 1) chapterIndex++ },
            onCenterTap = { showChrome = !showChrome },
        ) { pageIndex ->
            PageCanvas(
                entries = pages[pageIndex].entries,
                book = book,
                chapterHref = book.chapters.getOrNull(chapterIndex)?.href,
                style = style,
            )
        }

        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ReaderTopBar(
                title = title,
                chapterTitle = book.chapters.getOrNull(chapterIndex)?.title ?: "",
                onBack = navigateBack,
                onToc = { showToc = true },
            )
        }
        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            ReaderBottomBar(
                page = settledPage + 1,
                pageCount = pages.size,
                chapter = chapterIndex + 1,
                chapterCount = book.chapters.size,
                fontSize = fontSize,
                onFontSmaller = { moveFont(fontSize, -1) { fontSize = it } },
                onFontLarger = { moveFont(fontSize, +1) { fontSize = it } },
            )
        }

        if (showToc) {
            TocSheet(
                chapters = book.chapters,
                current = chapterIndex,
                onSelect = { index ->
                    showToc = false
                    if (index != chapterIndex) chapterIndex = index
                },
                onDismiss = { showToc = false },
            )
        }
    }
}

/**
 * PDF 阅读模式：各平台引擎把页面渲染成位图，HorizontalPager 翻页。位置即页码
 * （持久化在 history 的 spineIndex），进入时恢复到上次页。
 */
@Composable
private fun PdfPager(
    pdf: PdfDocument,
    startPage: Int,
    viewModel: ReaderViewModel,
    title: String,
    navigateBack: () -> Unit,
) {
    val pageCount = pdf.pageCount
    if (pageCount == 0) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("空 PDF 文档")
            TextButton(onClick = navigateBack) { Text("返回") }
        }
        return
    }
    var showChrome by remember { mutableStateOf(false) }
    val cache = remember { PdfBitmapCache() }
    val startPageSafe = startPage.coerceIn(0, pageCount - 1)
    var settledPage by remember { mutableIntStateOf(startPageSafe) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().toInt() }
        val heightPx = with(density) { maxHeight.toPx().toInt() }

        // Persist the page on settle, then render neighbours off the UI thread so the
        // next page flip is instant.
        LaunchedEffect(settledPage, pageCount, widthPx, heightPx) {
            val page = settledPage
            viewModel.recordPdfPosition(page, pageCount)
            for (neighbour in intArrayOf(page - 1, page + 1)) {
                if (neighbour in 0 until pageCount && cache.peek(neighbour) == null) {
                    val bitmap = withContext(Dispatchers.Default) {
                        pdf.renderPage(neighbour, widthPx, heightPx)
                    }
                    if (bitmap != null) cache.put(neighbour, bitmap)
                }
            }
        }

        ReaderPagerSurface(
            pageCount = pageCount,
            initialPage = startPageSafe,
            jumpToPage = startPageSafe,
            modifier = Modifier.fillMaxSize(),
            onSettledPage = { settledPage = it },
            onLeftEdgeTap = {},
            onRightEdgeTap = {},
            onCenterTap = { showChrome = !showChrome },
        ) { pageIndex ->
            val bitmap by produceState(cache.peek(pageIndex), pageIndex, widthPx, heightPx) {
                if (value == null) {
                    val rendered = withContext(Dispatchers.Default) {
                        pdf.renderPage(pageIndex, widthPx, heightPx)
                    }
                    if (rendered != null) cache.put(pageIndex, rendered)
                    value = rendered
                }
            }
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ReaderTopBar(
                title = title,
                chapterTitle = "第 ${settledPage + 1} / $pageCount 页",
                onBack = navigateBack,
                onToc = null,
            )
        }
        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                LinearProgressIndicator(
                    progress = { ((settledPage + 1).toFloat() / pageCount).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "第 ${settledPage + 1} / $pageCount 页",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** LRU page-bitmap cache for [PdfPager]; only touched from the main thread. */
private class PdfBitmapCache(maxEntries: Int = 8) {
    private val maxEntries = maxEntries.coerceAtLeast(3)
    private val bitmaps = HashMap<Int, ImageBitmap>()
    private val order = ArrayDeque<Int>()

    fun peek(index: Int): ImageBitmap? {
        touch(index)
        return bitmaps[index]
    }

    fun put(index: Int, bitmap: ImageBitmap) {
        touch(index)
        bitmaps[index] = bitmap
        while (order.size > maxEntries) {
            bitmaps.remove(order.removeFirst())
        }
    }

    private fun touch(index: Int) {
        order.remove(index)
        order.addLast(index)
    }
}

private inline fun moveFont(current: Int, direction: Int, set: (Int) -> Unit) {
    val at = ReaderFontSizes.indexOf(current).let { if (it < 0) 0 else it }
    set(ReaderFontSizes[(at + direction).coerceIn(0, ReaderFontSizes.size - 1)])
}

/** Production [LineMeasurer] over Compose's TextMeasurer — same measure call as the renderer. */
@Composable
private fun rememberBookLineMeasurer(style: ReaderStyle, contentWidth: Float): LineMeasurer {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    return remember(measurer, style, contentWidth, textColor) {
        object : LineMeasurer {
            override fun lines(
                paragraph: Block.Paragraph,
                style: ReaderStyle,
                maxWidthPx: Float,
                fontScale: Float,
            ): List<LineBox> {
                if (paragraph.spans.all { it.text.isEmpty() }) return emptyList()
                val layout = ParagraphLayout.measure(
                    paragraph = paragraph,
                    measurer = measurer,
                    style = style,
                    maxWidthPx = maxWidthPx,
                    textColor = textColor,
                    fontSizeSp = style.fontSizeSp * fontScale,
                    lineHeightPx = style.lineHeightPx * fontScale,
                )
                return (0 until layout.lineCount).map { line ->
                    LineBox(
                        heightPx = layout.getLineBottom(line) - layout.getLineTop(line),
                        startChar = layout.getLineStart(line),
                    )
                }
            }
        }
    }
}

/**
 * Draws one paginated page: measures each distinct paragraph at composition time with
 * the page's real content width, then paints line slices, images and rules top-down.
 * ParagraphLayout.measure is the same path the paginator measured with, so page breaks
 * match the drawn lines.
 */
@Composable
private fun PageCanvas(
    entries: List<PageEntry>,
    book: ReadableBook,
    chapterHref: String?,
    style: ReaderStyle,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val contentWidth = widthPx - style.horizontalPaddingPx * 2

        val measurer = rememberTextMeasurer()
        val textColor = MaterialTheme.colorScheme.onSurface
        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
        val paragraphs = remember(entries) {
            entries.filterIsInstance<PageEntry.Text>().map { it.paragraph }.distinct()
        }
        val layouts = remember(paragraphs, style, textColor, measurer, contentWidth) {
            paragraphs.associateWith { paragraph ->
                ParagraphLayout.measure(
                    paragraph = paragraph,
                    measurer = measurer,
                    style = style,
                    maxWidthPx = contentWidth,
                    textColor = textColor,
                    fontSizeSp = style.fontSizeSp * fontScaleOf(paragraph),
                    lineHeightPx = style.lineHeightPx * fontScaleOf(paragraph),
                )
            }
        }
        val images = mutableStateMapOf<String, ImageBitmap>()

        for (entry in entries) {
            if (entry is PageEntry.Picture && entry.image.src !in images) {
                LaunchedEffect(entry.image.src) {
                    val bytes = book.resourceBytes(entry.image.src, chapterHref) ?: return@LaunchedEffect
                    val bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
                    if (bitmap != null) images[entry.image.src] = bitmap
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            var y = style.topPaddingPx
            val left = style.horizontalPaddingPx
            val width = contentWidth

            for (entry in entries) {
                when (entry) {
                    is PageEntry.Text -> {
                        val paragraph = entry.paragraph
                        val layout = layouts.getValue(paragraph)
                        val top = layout.getLineTop(entry.fromLine)
                        val bottom = layout.getLineBottom(entry.toLine - 1)
                        translate(left, y - top) {
                            clipRect(0f, top, layout.size.width.toFloat(), bottom) {
                                drawText(layout, color = textColor)
                            }
                        }
                        y += bottom - top + Paginator.ParagraphGapPx
                    }

                    is PageEntry.Picture -> {
                        val bitmap = images[entry.image.src]
                        if (bitmap != null) {
                            val boxW = entry.widthPx
                            val boxH = entry.heightPx
                            val ratio = bitmap.width.toFloat() / bitmap.height
                            val wide = ratio >= boxW / boxH
                            val dstW = if (wide) boxW else boxH * ratio
                            val dstH = if (wide) boxW / ratio else boxH
                            drawImage(
                                image = bitmap,
                                dstOffset = IntOffset(
                                    (left + (boxW - dstW) / 2).toInt(),
                                    (y + (boxH - dstH) / 2).toInt(),
                                ),
                                dstSize = IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1)),
                            )
                        } else {
                            drawRoundRect(
                                color = mutedColor.copy(alpha = 0.25f),
                                topLeft = Offset(left, y),
                                size = Size(entry.widthPx, entry.heightPx),
                                cornerRadius = CornerRadius(12f, 12f),
                            )
                        }
                        y += entry.heightPx + Paginator.ImageGapPx
                    }

                    PageEntry.Rule -> {
                        drawLine(
                            color = mutedColor.copy(alpha = 0.5f),
                            start = Offset(left + width / 4, y + Paginator.RulerHeightPx / 2),
                            end = Offset(left + width * 3 / 4, y + Paginator.RulerHeightPx / 2),
                            strokeWidth = 1.5f,
                        )
                        y += Paginator.RulerHeightPx
                    }
                }
            }
        }
    }
}

private fun fontScaleOf(paragraph: Block.Paragraph): Float =
    if (paragraph.headingLevel > 0) ParagraphLayout.headingScale(paragraph.headingLevel) else 1f

@Composable
private fun ReaderTopBar(title: String, chapterTitle: String, onBack: () -> Unit, onToc: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onToc != null) {
            IconButton(onClick = onToc) { Icon(Icons.Filled.List, "目录") }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    pageCount: Int,
    chapter: Int,
    chapterCount: Int,
    fontSize: Int,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        LinearProgressIndicator(
            progress = { (chapter.toFloat() / chapterCount).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$chapter / $chapterCount 章 · $page / $pageCount 页",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onFontSmaller, enabled = fontSize > ReaderFontSizes.first()) {
                Text("A-", style = MaterialTheme.typography.titleMedium)
            }
            Text("${fontSize}pt", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onFontLarger, enabled = fontSize < ReaderFontSizes.last()) {
                Text("A+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun TocSheet(
    chapters: List<EpubBook.Chapter>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    TwineSheet(
        visible = true,
        onDismiss = onDismiss,
        peekFraction = 0.7f,
    ) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp).padding(bottom = 12.dp)) {
            items(chapters.size) { index ->
                val chapter = chapters[index]
                Text(
                    chapter.title.ifBlank { "第 ${index + 1} 节" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
