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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import us.wangxy.voicebook.theme.LocalTwineTokens
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.ThemeBar
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
import us.wangxy.voicebook.reader.store.PdfPageFit
import us.wangxy.voicebook.reader.store.ReaderFontFamily
import us.wangxy.voicebook.reader.store.ReaderPageTurn
import us.wangxy.voicebook.reader.store.ReaderStateController
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val ReaderFontSizes = listOf(14, 16, 18, 20, 24)

/** 缩放后重渲染请求位图的长边上限(px),防止 4x 放大在长页上撑爆内存。 */
private const val MaxRenderEdgePx = 4096f

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

    // 亮度/屏幕常亮(Android):进入应用设置,离开页面自动复位
    val prefs by stateController.state.collectAsStateWithLifecycle()
    ScreenBrightnessEffect(prefs.brightness, prefs.keepScreenOn)

    VoiceBookTheme(seedState = ambient) {
    val paper = LocalTwineTokens.current.paper
    // 纸色铺满全屏(含状态栏/导航栏),盖住主题渐变;内容避开系统栏。
    // 用 systemBars 而非 safeDrawing:排除刘海 cutout 的额外下移,标题紧贴状态栏下方。
    Box(Modifier.fillMaxSize().background(paper).windowInsetsPadding(WindowInsets.systemBars)) {
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
                stateController = stateController,
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
    val prefs by stateController.state.collectAsStateWithLifecycle()
    var fontSize by remember { mutableIntStateOf(stateController.state.value.fontSizeSp) }
    var lineHeight by remember { mutableFloatStateOf(stateController.state.value.lineHeightFactor) }
    var marginDp by remember { mutableIntStateOf(stateController.state.value.pageMarginDp) }
    var pageTurn by remember { mutableStateOf(stateController.state.value.pageTurn) }
    var fontFamily by remember { mutableStateOf(stateController.state.value.fontFamily) }
    var firstLineIndent by remember { mutableStateOf(stateController.state.value.firstLineIndent) }
    var chapterIndex by remember {
        mutableIntStateOf(startAnchor?.first?.coerceIn(0, book.chapters.size - 1) ?: 0)
    }
    var showChrome by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(fontSize) { stateController.setFontSize(fontSize) }
    LaunchedEffect(lineHeight) { stateController.setLineHeight(lineHeight) }
    LaunchedEffect(marginDp) { stateController.setPageMargin(marginDp) }
    LaunchedEffect(pageTurn) { stateController.setPageTurn(pageTurn) }
    LaunchedEffect(fontFamily) { stateController.setFontFamily(fontFamily) }
    LaunchedEffect(firstLineIndent) { stateController.setFirstLineIndent(firstLineIndent) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val style = ReaderStyle(
            fontSizeSp = fontSize.toFloat(),
            lineHeightFactor = lineHeight,
            horizontalPaddingPx = with(density) { marginDp.dp.toPx() },
            topPaddingPx = with(density) { 20.dp.toPx() },
            bottomPaddingPx = with(density) { 28.dp.toPx() },
            fontFamily = mapReaderFontFamily(fontFamily),
            firstLineIndent = firstLineIndent,
        )
        val contentWidth = widthPx - style.horizontalPaddingPx * 2
        val contentHeight = heightPx - style.topPaddingPx - style.bottomPaddingPx

        val blocks = remember(chapterIndex) { book.chapterBlocks(chapterIndex) }
        val lineMeasurer = rememberBookLineMeasurer(style, contentWidth)
        val pages = remember(chapterIndex, fontSize, lineHeight, marginDp, fontFamily, firstLineIndent, widthPx, heightPx) {
            Paginator.paginate(
                blocks = blocks,
                measurer = lineMeasurer,
                style = style,
                layout = PageLayout(contentWidth, contentHeight),
            )
        }

        // 当前落定页的锚点:字号/行距/边距变化触发重排后,按它跳回同一位置而不丢进度。
        // 打开书时先一次性应用保存的锚点;之后章节切换都从第 0 页进入。
        var currentAnchor by remember(chapterIndex) { mutableIntStateOf(0) }
        var restored by remember { mutableStateOf(false) }
        var settledPage by remember { mutableIntStateOf(0) }
        // 显式跳页通道:进度条拖动 / 自动翻页 / 章节打开与重排后的定位。落定页码绝不
        // 回灌成跳页目标——卷页表面的 current 回报滞后于动画且 snapshotFlow 会合并
        // 连续翻页,若据此回灌 jumpToPage,迟到的落定回调会把刚翻到的页拽回去,
        // 表现为"无法翻页,一直停在当前页"。
        var seekTarget by remember { mutableStateOf<Int?>(null) }
        var autoAdvance by remember { mutableStateOf<Int?>(null) }
        var jumpTarget by remember { mutableStateOf<Int?>(null) }

        // 章节打开(用保存的锚点,仅首次)/章节切换(回到页首)/重排(回到当前锚点)时
        // 定位一次;到位后由 onSettledPage 清除目标。
        LaunchedEffect(chapterIndex, pages, pageTurn) {
            val anchor = if (restored) currentAnchor else {
                restored = true
                startAnchor?.second ?: 0
            }
            currentAnchor = anchor
            jumpTarget = Paginator.pageForOffset(pages, anchor)
        }

        // 自动阅读:按设定间隔走页,章末自动进下一章;用户翻页(落定)后计时自然重置
        LaunchedEffect(prefs.autoReadMillis, settledPage, chapterIndex) {
            if (prefs.autoReadMillis > 0L) {
                delay(prefs.autoReadMillis)
                when {
                    settledPage < pages.size - 1 -> autoAdvance = settledPage + 1
                    chapterIndex < book.chapters.size - 1 -> chapterIndex += 1
                }
            }
        }

        // Persist the position whenever the page settles. Re-collecting on chapter/font
        // change re-emits the settled page once — the save is idempotent.
        LaunchedEffect(settledPage, pages, chapterIndex) {
            val anchor = pages.getOrNull(settledPage)?.anchorOffset ?: 0
            val percent = chapterIndex * 100 / book.chapters.size.coerceAtLeast(1)
            viewModel.recordPosition(chapterIndex, anchor, percent)
        }

        // 页面文本布局/图片缓存挂在页面组合之外:卷页表面以 (当前页, 边缘位置) 为
        // key,翻页动画期间页面组合每帧销毁重建,缓存若放页面内会每帧失效,整页段落
        // 逐帧重新测量,拖拽卷页会掉到个位帧率(PDF 页只是读缓存位图,无此问题)。
        // 提升到会话层后每帧只剩纯绘制,文案排版随 pages 重建而失效。
        val pageLayoutCache = remember(pages) { HashMap<Block.Paragraph, TextLayoutResult>() }
        val pageImages = remember(pages) { mutableStateMapOf<String, ImageBitmap>() }

        ReaderPagerSurface(
            pageCount = pages.size,
            initialPage = 0,
            jumpToPage = autoAdvance ?: seekTarget ?: jumpTarget,
            pageTurn = pageTurn,
            chromeVisible = showChrome,
            modifier = Modifier.fillMaxSize(),
            onSettledPage = { page ->
                settledPage = page
                currentAnchor = pages.getOrNull(page)?.anchorOffset ?: currentAnchor
                // 跳页/拖动/自动翻页已落定:清除目标,避免残留目标卡住后续定位
                if (page == jumpTarget) jumpTarget = null
                if (page == seekTarget) seekTarget = null
                if (page == autoAdvance) autoAdvance = null
            },
            onLeftEdgeTap = { if (chapterIndex > 0) chapterIndex-- },
            onRightEdgeTap = { if (chapterIndex < book.chapters.size - 1) chapterIndex++ },
            onCenterTap = { showChrome = !showChrome },
            waitForDoubleTap = false,
        ) { pageIndex ->
            PageCanvas(
                entries = pages[pageIndex].entries,
                book = book,
                chapterHref = book.chapters.getOrNull(chapterIndex)?.href,
                style = style,
                layoutCache = pageLayoutCache,
                images = pageImages,
            )
        }

        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ReaderTopBar(
                title = title,
                chapterTitle = book.chapters.getOrNull(chapterIndex)?.title ?: "",
                onBack = navigateBack,
                onToc = { showToc = true },
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            val seekPage = (seekTarget ?: settledPage).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            val percent = (((chapterIndex + (settledPage + 1f) / pages.size) / book.chapters.size) * 100)
                .toInt().coerceIn(0, 100)
            ReaderBottomBar(
                page = settledPage + 1,
                pageCount = pages.size,
                chapter = chapterIndex + 1,
                chapterCount = book.chapters.size,
                percent = percent,
                pageFraction = if (pages.size <= 1) 0f else seekPage.toFloat() / (pages.size - 1),
                onSeek = { fraction ->
                    seekTarget = (fraction * (pages.size - 1)).roundToInt().coerceIn(0, pages.size - 1)
                },
                onSeekFinished = {
                    // 拖回当前页不会有落定回调,这里兜底清零;其余交给 onSettledPage
                    if (seekTarget == settledPage) seekTarget = null
                },
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

        if (showSettings) {
            ReaderSettingsSheet(
                pdfMode = false,
                pdfPageFit = PdfPageFit.FitPage,
                onPdfPageFit = {},
                fontSize = fontSize,
                onFontSize = { fontSize = it },
                lineHeight = lineHeight,
                onLineHeight = { lineHeight = it },
                marginDp = marginDp,
                onMargin = { marginDp = it },
                fontFamily = fontFamily,
                onFontFamily = { fontFamily = it },
                firstLineIndent = firstLineIndent,
                onFirstLineIndent = { firstLineIndent = it },
                brightness = prefs.brightness,
                onBrightness = { stateController.setBrightness(it) },
                keepScreenOn = prefs.keepScreenOn,
                onKeepScreenOn = { stateController.setKeepScreenOn(it) },
                autoReadMillis = prefs.autoReadMillis,
                onAutoRead = { stateController.setAutoRead(it) },
                pageTurn = pageTurn,
                onPageTurn = { pageTurn = it },
                onDismiss = { showSettings = false },
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
    stateController: ReaderStateController,
    viewModel: ReaderViewModel,
    title: String,
    navigateBack: () -> Unit,
) {
    val pageCount = pdf.pageCount
    if (pageCount == 0) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("空 PDF 文档")
            TextButton(onClick = navigateBack) { Text("返回") }
        }
        return
    }
    var showChrome by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pageTurn by remember { mutableStateOf(stateController.state.value.pageTurn) }
    LaunchedEffect(pageTurn) { stateController.setPageTurn(pageTurn) }
    val prefs by stateController.state.collectAsStateWithLifecycle()
    val pdfPageFit = prefs.pdfPageFit
    val cache = remember { PdfBitmapCache() }
    val zoom = remember { PdfZoomState() }
    val zoomScope = rememberCoroutineScope()
    val startPageSafe = startPage.coerceIn(0, pageCount - 1)
    var settledPage by remember { mutableIntStateOf(startPageSafe) }
    // 进度条拖动与自动翻页共用的跳页通道;落定后清零
    var seekTarget by remember { mutableStateOf<Int?>(null) }
    var autoAdvance by remember { mutableStateOf<Int?>(null) }
    val tokens = LocalTwineTokens.current
    // 显示层把 PDF 的白底黑字映射到皮肤纸墨色,缓存仍保留原始白底位图,切皮肤无重渲染
    val toneFilter = remember(tokens) { paperToneFilter(tokens.paper, tokens.ink) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().toInt() }
        val heightPx = with(density) { maxHeight.toPx().toInt() }
        val viewportW = widthPx.toFloat()
        val viewportH = heightPx.toFloat()

        // 翻页/切适配后按新页面内容尺寸收敛缩放:未放大时回到页首(适宽对齐上边缘),
        // 已放大时保留倍率与大致位置,仅钳制到合法域
        LaunchedEffect(settledPage, pdfPageFit, widthPx, heightPx) {
            val size = pdf.pageSize(settledPage)
            val base = pdfPageFit.displayScale(size.widthPt, size.heightPt, viewportW, viewportH)
            if (zoom.scale <= 1f) {
                zoom.resetToTop(size.heightPt * base, viewportH)
            } else {
                zoom.reclamp(size.widthPt * base, size.heightPt * base, viewportW, viewportH)
            }
        }

        // Persist the page on settle, then render neighbours off the UI thread so the
        // next page flip is instant. 邻页只取 1x 档,高清档留给当前页,防 OOM。
        LaunchedEffect(settledPage, pageCount, widthPx, heightPx) {
            val page = settledPage
            viewModel.recordPdfPosition(page, pageCount)
            for (neighbour in intArrayOf(page - 1, page + 1)) {
                if (neighbour in 0 until pageCount && cache.peek(neighbour, PdfBitmapCache.BaseBucket) == null) {
                    val bitmap = withContext(Dispatchers.Default) {
                        pdf.renderPage(neighbour, widthPx, heightPx)
                    }
                    if (bitmap != null) cache.put(neighbour, PdfBitmapCache.BaseBucket, bitmap)
                }
            }
        }

        // 自动阅读:按设定间隔走页,末页停止;用户翻页(落定)后计时自然重置
        LaunchedEffect(prefs.autoReadMillis, settledPage) {
            if (prefs.autoReadMillis > 0L) {
                delay(prefs.autoReadMillis)
                if (settledPage < pageCount - 1) autoAdvance = settledPage + 1
            }
        }

        ReaderPagerSurface(
            pageCount = pageCount,
            initialPage = startPageSafe,
            jumpToPage = autoAdvance ?: seekTarget ?: startPageSafe,
            pageTurn = pageTurn,
            chromeVisible = showChrome,
            modifier = Modifier.fillMaxSize(),
            onSettledPage = { page ->
                settledPage = page
                if (page == seekTarget) seekTarget = null
                if (page == autoAdvance) autoAdvance = null
            },
            onLeftEdgeTap = {},
            onRightEdgeTap = {},
            onCenterTap = { showChrome = !showChrome },
            waitForDoubleTap = true,
        ) { pageIndex ->
            // 适配系数:适屏 = 整页落入视口;适宽 = 宽度撑满(纵向超高,可拖动)
            val pageSize = remember(pageIndex) { pdf.pageSize(pageIndex) }
            val baseScale = remember(pageSize, pdfPageFit, widthPx, heightPx) {
                pdfPageFit.displayScale(pageSize.widthPt, pageSize.heightPt, viewportW, viewportH)
            }
            val contentW = pageSize.widthPt * baseScale
            val contentH = pageSize.heightPt * baseScale
            // 当前页按缩放档位取高清位图,非落定页保持 1x 档;zoom.scale 变化时
            // 档位跨越阈值会自动触发重渲染,重渲染到达前继续显示旧位图
            val isSettled = pageIndex == settledPage
            val bucket = if (isSettled) pdfRenderBucket(baseScale * zoom.scale) else PdfBitmapCache.BaseBucket
            val bitmap by produceState(cache.peek(pageIndex, bucket), pageIndex, bucket, widthPx, heightPx) {
                if (value == null) {
                    // 请求尺寸 = 视口 × 档位,长边钳到 4096(平台 MaxRenderScale=3 仍是硬上限)
                    val renderScale = minOf(bucket, MaxRenderEdgePx / maxOf(widthPx, heightPx).coerceAtLeast(1))
                    val rendered = withContext(Dispatchers.Default) {
                        pdf.renderPage(pageIndex, (widthPx * renderScale).toInt(), (heightPx * renderScale).toInt())
                    }
                    if (rendered != null) cache.put(pageIndex, bucket, rendered)
                    value = rendered
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tokens.paper)
                    .pdfZoomable(zoom, zoomScope, contentW, contentH, viewportW, viewportH),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    Box(
                        Modifier
                            .size(with(density) { contentW.toDp() }, with(density) { contentH.toDp() })
                            .graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offsetX
                                translationY = zoom.offsetY
                            },
                    ) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = toneFilter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ReaderTopBar(
                title = title,
                chapterTitle = "第 ${settledPage + 1} / $pageCount 页",
                onBack = navigateBack,
                onToc = null,
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            val seekPage = (seekTarget ?: settledPage).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Slider(
                    value = if (pageCount <= 1) 0f else seekPage.toFloat() / (pageCount - 1),
                    onValueChange = { fraction ->
                        seekTarget = (fraction * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
                    },
                    onValueChangeFinished = {
                        if (seekTarget == settledPage) seekTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "第 ${settledPage + 1} / $pageCount 页 · " +
                        "${(((settledPage + 1f) / pageCount) * 100).toInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        if (showSettings) {
            ReaderSettingsSheet(
                pdfMode = true,
                pdfPageFit = prefs.pdfPageFit,
                onPdfPageFit = { stateController.setPdfPageFit(it) },
                fontSize = prefs.fontSizeSp,
                onFontSize = {},
                lineHeight = prefs.lineHeightFactor,
                onLineHeight = {},
                marginDp = prefs.pageMarginDp,
                onMargin = {},
                fontFamily = prefs.fontFamily,
                onFontFamily = {},
                firstLineIndent = prefs.firstLineIndent,
                onFirstLineIndent = {},
                brightness = prefs.brightness,
                onBrightness = { stateController.setBrightness(it) },
                keepScreenOn = prefs.keepScreenOn,
                onKeepScreenOn = { stateController.setKeepScreenOn(it) },
                autoReadMillis = prefs.autoReadMillis,
                onAutoRead = { stateController.setAutoRead(it) },
                pageTurn = pageTurn,
                onPageTurn = { pageTurn = it },
                onDismiss = { showSettings = false },
            )
        }
    }
}

/**
 * LRU page-bitmap cache for [PdfPager]; only touched from the main thread.
 * Key = (页码, 渲染档位):缩放后的高清位图与 1x 位图共存;按像素字节预算淘汰,
 * 高清大图会挤掉旧条目而不是无限累积。
 */
private class PdfBitmapCache(private val maxBytes: Long = 160L * 1024 * 1024) {
    private data class Key(val page: Int, val bucket: Float)

    private val entries = HashMap<Key, ImageBitmap>()
    private val order = ArrayDeque<Key>()
    private var bytes = 0L

    fun peek(page: Int, bucket: Float): ImageBitmap? {
        val key = Key(page, bucket)
        touch(key)
        return entries[key]
    }

    fun put(page: Int, bucket: Float, bitmap: ImageBitmap) {
        val key = Key(page, bucket)
        entries[key]?.let { old ->
            bytes -= sizeOf(old)
            order.remove(key)
        }
        entries[key] = bitmap
        order.addLast(key)
        bytes += sizeOf(bitmap)
        while (bytes > maxBytes && order.size > 1) {
            val evicted = order.removeFirst()
            entries.remove(evicted)?.let { bytes -= sizeOf(it) }
        }
    }

    private fun touch(key: Key) {
        if (entries.containsKey(key)) {
            order.remove(key)
            order.addLast(key)
        }
    }

    private fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4L

    companion object {
        /** 基础渲染档(1x 视口)。 */
        const val BaseBucket = 1f
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
 * Draws one paginated page: paints line slices, images and rules top-down. Paragraph
 * layouts and images come from the session-level [layoutCache]/[images] (owned by
 * BookPager) — the curl surface rebuilds page composition every animation frame, so
 * anything measured or loaded inside this composable would be thrown away per frame.
 * ParagraphLayout.measure (via the cache) is the same path the paginator measured
 * with, so page breaks match the drawn lines.
 */
@Composable
private fun PageCanvas(
    entries: List<PageEntry>,
    book: ReadableBook,
    chapterHref: String?,
    style: ReaderStyle,
    layoutCache: MutableMap<Block.Paragraph, TextLayoutResult>,
    images: MutableMap<String, ImageBitmap>,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(LocalTwineTokens.current.paper)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val contentWidth = widthPx - style.horizontalPaddingPx * 2

        val measurer = rememberTextMeasurer()
        val tokens = LocalTwineTokens.current
        val textColor = tokens.ink
        val mutedColor = tokens.inkFaded

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
                        val layout = layoutCache.getOrPut(paragraph) {
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
                        val top = layout.getLineTop(entry.fromLine)
                        val bottom = layout.getLineBottom(entry.toLine - 1)
                        translate(left, y - top) {
                            clipRect(0f, top, layout.size.width.toFloat(), bottom) {
                                drawText(layout, color = textColor)
                            }
                        }
                        y += bottom - top + style.paragraphGapPx
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

/**
 * 显示层纸墨映射:输出 = 输入 × (纸 − 墨) + 墨,即输入黑→墨色、输入白→纸色、
 * 中间色线性过渡。用于给白底 PDF 位图染上当前皮肤的纸墨色,无需重渲染。
 */
private fun paperToneFilter(paper: Color, ink: Color): ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            paper.red - ink.red, 0f, 0f, 0f, ink.red,
            0f, paper.green - ink.green, 0f, 0f, ink.green,
            0f, 0f, paper.blue - ink.blue, 0f, ink.blue,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: (() -> Unit)?,
    onSettings: () -> Unit,
) {
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
        IconButton(onClick = onSettings) {
            Text("Aa", style = MaterialTheme.typography.titleMedium)
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
    percent: Int,
    pageFraction: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
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
        Slider(
            value = pageFraction.coerceIn(0f, 1f),
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$chapter / $chapterCount 章 · $page / $pageCount 页 · $percent%",
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

/** 行距档位(行距系数,标签)。 */
private val ReaderLineHeights = listOf(
    1.4f to "紧凑", 1.65f to "标准", 1.9f to "宽松", 2.2f to "特宽",
)

/** 边距档位(左右边距 dp,标签)。 */
private val ReaderMargins = listOf(16 to "窄", 24 to "标准", 36 to "宽")

/** 自动阅读档位(翻页间隔毫秒,标签)。 */
private val ReaderAutoReads = listOf(
    0L to "关", 15_000L to "慢", 10_000L to "中", 6_000L to "快",
)

/** 字体族档位。 */
private val ReaderFontFamilies = listOf(
    ReaderFontFamily.Default to "系统默认",
    ReaderFontFamily.Serif to "衬线",
    ReaderFontFamily.Sans to "无衬线",
    ReaderFontFamily.Mono to "等宽",
)

private fun mapReaderFontFamily(value: ReaderFontFamily): FontFamily = when (value) {
    ReaderFontFamily.Default -> FontFamily.Default
    ReaderFontFamily.Serif -> FontFamily.Serif
    ReaderFontFamily.Sans -> FontFamily.SansSerif
    ReaderFontFamily.Mono -> FontFamily.Monospace
}

/**
 * 阅读设置面板(TwineSheet 承载)。EPUB/TXT 显示全部排版与阅读体验项;
 * PDF 为固定版式,排版项(字号/行距/边距/字体/缩进)不出现,保留页面适配与体验项。
 */
@Composable
private fun ReaderSettingsSheet(
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
            SheetSection("主题") { ThemeBar() }
            if (pdfMode) {
                SheetSection("页面适配") {
                    OptionRow(
                        listOf(PdfPageFit.FitPage to "适屏", PdfPageFit.FitWidth to "适宽"),
                        pdfPageFit,
                        onPdfPageFit,
                    )
                }
            }
            if (!pdfMode) {
                SheetSection("字号") {
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
                SheetSection("行距") { OptionRow(ReaderLineHeights, lineHeight, onLineHeight) }
                SheetSection("边距") { OptionRow(ReaderMargins, marginDp, onMargin) }
                SheetSection("字体") { OptionRow(ReaderFontFamilies, fontFamily, onFontFamily) }
                SheetSection("缩进") {
                    OptionRow(listOf(true to "缩进", false to "不缩进"), firstLineIndent, onFirstLineIndent)
                }
            }
            if (SupportsBrightness) {
                SheetSection("亮度") {
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
                SwitchSection("屏幕常亮", keepScreenOn, onKeepScreenOn)
            }
            SheetSection("自动阅读") { OptionRow(ReaderAutoReads, autoReadMillis, onAutoRead) }
            if (SupportsCurlPageTurn) {
                SheetSection("翻页方式") {
                    OptionRow(
                        listOf(ReaderPageTurn.Curl to "卷页", ReaderPageTurn.Slide to "平移"),
                        pageTurn,
                        onPageTurn,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        content()
    }
}

@Composable
private fun SwitchSection(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SheetSection(title) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (checked) "已开启" else "已关闭",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun <T> OptionRow(options: List<Pair<T, String>>, current: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == current,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
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
