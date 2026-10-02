package us.wangxy.voicebook.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import us.wangxy.voicebook.listen.ListenBar
import us.wangxy.voicebook.listen.ListenController
import us.wangxy.voicebook.listen.ListenStatus
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.paginate.PageLayout
import us.wangxy.voicebook.reader.paginate.Paginator
import us.wangxy.voicebook.reader.paginate.ReaderPage
import us.wangxy.voicebook.reader.render.ReaderStyle
import us.wangxy.voicebook.reader.store.PdfPageFit
import us.wangxy.voicebook.reader.store.ReaderStateController

@Composable
internal fun BookPager(
    book: ReadableBook,
    startAnchor: Pair<Int, Int>?,
    stateController: ReaderStateController,
    viewModel: ReaderViewModel,
    bookId: Int,
    title: String,
    /** 该书评论区。入口在顶栏，抽屉与目录/设置一样画在本页里。 */
    comments: ReaderCommentsController,
    /** 该书评论总数，透传给顶栏角标。 */
    commentCount: Int,
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
    var showComments by remember { mutableStateOf(false) }
    val listen = koinInject<ListenController>()
    val listenState by listen.state.collectAsStateWithLifecycle()
    val listeningHere = listenState.bookId == bookId
    val following = listeningHere && listenState.status == ListenStatus.Playing

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

        val widthKey = widthPx.roundToInt()
        val heightKey = heightPx.roundToInt()
        val layoutKey = "$fontSize|$lineHeight|$marginDp|$fontFamily|$firstLineIndent|$widthKey|$heightKey"
        val lineMeasurer = rememberBookLineMeasurer(style, contentWidth)
        // 页流在阅读会话里只增不拆:邻章接到两端,跨章就是普通的下一页,不再换一整章列表。
        val reading = remember { ReadingPages() }
        var currentAnchor by remember { mutableIntStateOf(startAnchor?.second ?: 0) }
        var settledPage by remember { mutableIntStateOf(0) }
        var settledIndex by remember { mutableIntStateOf(0) }
        // 进度条拖动记的是章内页码;自动翻页记的是页流下标。二者都只在目标变化时跳一次。
        var seekTarget by remember { mutableStateOf<Int?>(null) }
        var autoAdvance by remember { mutableStateOf<Int?>(null) }
        var edgeStep by remember { mutableIntStateOf(0) }
        var tocChapter by remember { mutableStateOf<Int?>(null) }

        fun pagesOf(chapter: Int): List<ReaderPage> {
            if (chapter !in book.chapters.indices) return emptyList()
            if (contentWidth <= 1f || contentHeight <= 1f) return emptyList()
            val laid = Paginator.paginate(
                book.chapterBlocks(chapter),
                lineMeasurer,
                style,
                PageLayout(contentWidth, contentHeight),
            )
            return laid.ifEmpty { listOf(ReaderPage(emptyList(), 0)) }
        }

        // 打开、以及字号/边距/视口变化:按锚点重排已经接上的章节。第一次只排当前章,邻章随后接上。
        if (widthKey > 1 && heightKey > 1 && book.chapters.isNotEmpty() && reading.layoutKey != layoutKey) {
            val chapter = chapterIndex.coerceIn(0, book.chapters.lastIndex)
            val anchor = if (reading.slots.isEmpty()) startAnchor?.second ?: 0 else currentAnchor
            val chapters = reading.slots.map { it.chapter }.distinct().ifEmpty { listOf(chapter) }
            val rebuilt = buildList {
                for (ch in chapters) {
                    pagesOf(ch).forEachIndexed { index, page -> add(StreamSlot(ch, index, page)) }
                }
            }
            val chapterPages = rebuilt.filter { it.chapter == chapter }.map { it.page }
            val page = if (chapterPages.isEmpty()) 0 else Paginator.pageForOffset(chapterPages, anchor)
            val index = indexOfSlot(rebuilt, chapter, page).let { if (it < 0) 0 else it }
            val slot = rebuilt.getOrNull(index)
            reading.layoutKey = layoutKey
            reading.slots = rebuilt
            reading.focus = index
            if (slot != null) {
                chapterIndex = slot.chapter
                settledPage = slot.pageInChapter
                settledIndex = index
                currentAnchor = slot.page.anchorOffset
            }
        }

        // 读到已加载范围的两端时,再接一章。往后接不改下标;往前接要把下标同步后移,看见的还是同一页。
        LaunchedEffect(chapterIndex, reading.slots.firstOrNull()?.chapter, reading.slots.lastOrNull()?.chapter, layoutKey) {
            val current = reading.slots
            if (current.isEmpty()) return@LaunchedEffect
            val first = current.first().chapter
            val last = current.last().chapter
            var nextSlots = current
            var shift = 0
            if (chapterIndex == first && first > 0) {
                val (extended, added) = extendBackward(current, first - 1, pagesOf(first - 1))
                nextSlots = extended
                shift = added
            }
            if (chapterIndex == last && last < book.chapters.lastIndex) {
                nextSlots = extendForward(nextSlots, last + 1, pagesOf(last + 1))
            }
            if (nextSlots === current) return@LaunchedEffect
            if (shift > 0) {
                val base = reading.focus ?: settledIndex
                reading.focus = base + shift
                settledIndex = base + shift
                autoAdvance = autoAdvance?.plus(shift)
            }
            reading.slots = nextSlots
        }

        LaunchedEffect(tocChapter) {
            val chapter = tocChapter ?: return@LaunchedEffect
            tocChapter = null
            if (chapter !in book.chapters.indices) return@LaunchedEffect
            val existing = indexOfSlot(reading.slots, chapter, 0)
            if (existing >= 0) {
                reading.focus = existing
                return@LaunchedEffect
            }
            val built = pagesOf(chapter).mapIndexed { index, page -> StreamSlot(chapter, index, page) }
            if (built.isEmpty()) return@LaunchedEffect
            reading.slots = built
            reading.focus = 0
            chapterIndex = chapter
            settledPage = 0
            settledIndex = 0
            currentAnchor = built.first().page.anchorOffset
            seekTarget = null
            autoAdvance = null
        }

        // 邻章还没接上时,章界手势先把那一章接进来再落到相邻页。接上之后走翻页动画,不会进这里。
        LaunchedEffect(edgeStep) {
            if (edgeStep == 0 || reading.slots.isEmpty()) return@LaunchedEffect
            val step = edgeStep
            edgeStep = 0
            var current = reading.slots
            var index = reading.focus ?: settledIndex
            if (step < 0 && index <= 0) {
                val first = current.first().chapter
                val (extended, added) = extendBackward(current, first - 1, pagesOf(first - 1))
                current = extended
                index += added
            }
            if (step > 0 && index >= current.lastIndex) {
                val last = current.last().chapter
                current = extendForward(current, last + 1, pagesOf(last + 1))
            }
            val dest = index + step
            if (dest !in current.indices) return@LaunchedEffect
            reading.slots = current
            reading.focus = dest
        }

        // 听书跟读:正在朗读的句子落到别的页(或别的章)时翻过去。只在播放中跟随,暂停时随便翻。
        val spoken = listenState.sentence
        LaunchedEffect(following, listenState.chapterIndex, spoken?.start, reading.slots) {
            if (!following || spoken == null || reading.slots.isEmpty()) return@LaunchedEffect
            val chapter = listenState.chapterIndex
            val slots = reading.slots
            if (slots.none { it.chapter == chapter }) {
                tocChapter = chapter
                return@LaunchedEffect
            }
            val chapterPages = slots.filter { it.chapter == chapter }.map { it.page }
            val index = indexOfSlot(slots, chapter, Paginator.pageForOffset(chapterPages, spoken.start))
            if (index >= 0 && index != settledIndex) autoAdvance = index
        }

        // 听书时由朗读驱动翻页,定时自动翻页让位。
        LaunchedEffect(prefs.autoReadMillis, settledIndex, reading.slots.size, listeningHere) {
            if (listeningHere || prefs.autoReadMillis <= 0L || reading.slots.isEmpty()) return@LaunchedEffect
            delay(prefs.autoReadMillis)
            if (settledIndex < reading.slots.lastIndex) autoAdvance = settledIndex + 1
        }

        LaunchedEffect(chapterIndex, currentAnchor) {
            // Following spoken sentences must not overwrite the independent reading position.
            if (following) return@LaunchedEffect
            val percent = chapterIndex * 100 / book.chapters.size.coerceAtLeast(1)
            viewModel.recordPosition(chapterIndex, currentAnchor, percent)
        }

        // 文本布局缓存跟排版走,不跟页流走:跨章只是列表变长,缓存留着。
        // 卷页动画每帧会拆掉页面组合,缓存若放进页面里,拖拽会掉到个位帧率。
        val pageLayoutCache = remember(layoutKey) { HashMap<Block.Paragraph, TextLayoutResult>() }
        val pageImages = remember { mutableStateMapOf<String, ImageBitmap>() }
        val slots = reading.slots
        val seekIndex = seekTarget?.let { page ->
            indexOfSlot(slots, chapterIndex, page).takeIf { it >= 0 }
        }
        val pagerInitial = remember(pageTurn, slots.isEmpty()) {
            val count = slots.size
            val raw = reading.focus ?: settledIndex
            if (count <= 0) 0 else raw.coerceIn(0, count - 1)
        }

        if (slots.isNotEmpty()) {
            ReaderPagerSurface(
                pageCount = slots.size,
                initialPage = pagerInitial,
                jumpToPage = seekIndex ?: autoAdvance ?: reading.focus,
                pageTurn = pageTurn,
                chromeVisible = showChrome,
                modifier = Modifier.fillMaxSize(),
                onSettledPage = { index ->
                    val pending = reading.focus
                    if (pending != null && index != pending && seekTarget == null && autoAdvance == null) {
                        return@ReaderPagerSurface
                    }
                    val slot = slots.getOrNull(index) ?: return@ReaderPagerSurface
                    settledIndex = index
                    settledPage = slot.pageInChapter
                    chapterIndex = slot.chapter
                    currentAnchor = slot.page.anchorOffset
                    reading.focus = null
                    if (slot.pageInChapter == seekTarget) seekTarget = null
                    if (index == autoAdvance) autoAdvance = null
                },
                onLeftEdgeTap = { if (chapterIndex > 0) edgeStep = -1 },
                onRightEdgeTap = { if (chapterIndex < book.chapters.lastIndex) edgeStep = 1 },
                onCenterTap = { showChrome = !showChrome },
                waitForDoubleTap = false,
                pageKey = { index ->
                    val slot = slots.getOrNull(index)
                    if (slot == null) index else slot.chapter to slot.pageInChapter
                },
            ) { pageIndex ->
                val slot = slots.getOrNull(pageIndex) ?: return@ReaderPagerSurface
                PageCanvas(
                    entries = slot.page.entries,
                    book = book,
                    chapterHref = book.chapters.getOrNull(slot.chapter)?.href,
                    style = style,
                    layoutCache = pageLayoutCache,
                    images = pageImages,
                    highlight = if (listeningHere && slot.chapter == listenState.chapterIndex) {
                        listenState.sentence?.let { it.start to it.end }
                    } else {
                        null
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ReaderTopBar(
                title = title,
                chapterTitle = book.chapters.getOrNull(chapterIndex)?.title ?: "",
                onBack = navigateBack,
                onToc = { showToc = true },
                onSettings = { showSettings = true },
                onComments = {
                    showChrome = false
                    comments.open()
                    showComments = true
                },
                commentCount = commentCount,
                onListen = {
                    showChrome = false
                    viewModel.historyEntry()?.let { listen.start(book, it, chapterIndex, currentAnchor) }
                },
            )
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (listeningHere) ListenBar(listenState, listen)
            AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut()) {
                val chapterPageCount = reading.slots.count { it.chapter == chapterIndex }.coerceAtLeast(1)
                val seekPage = (seekTarget ?: settledPage).coerceIn(0, chapterPageCount - 1)
                val percent = (((chapterIndex + (settledPage + 1f) / chapterPageCount) / book.chapters.size) * 100)
                    .toInt().coerceIn(0, 100)
                ReaderBottomBar(
                    page = settledPage + 1,
                    pageCount = chapterPageCount,
                    chapter = chapterIndex + 1,
                    chapterCount = book.chapters.size,
                    percent = percent,
                    pageFraction = if (chapterPageCount <= 1) 0f else seekPage.toFloat() / (chapterPageCount - 1),
                    onSeek = { fraction ->
                        seekTarget = (fraction * (chapterPageCount - 1)).roundToInt().coerceIn(0, chapterPageCount - 1)
                    },
                    onSeekFinished = {
                        // 拖回当前页不会有落定回调,这里兜底清零;其余交给 onSettledPage
                        if (seekTarget == settledPage) seekTarget = null
                    },
                    fontSize = fontSize,
                    onFontSmaller = { moveFont(fontSize, -1) { fontSize = it } },
                    onFontLarger = { moveFont(fontSize, +1) { fontSize = it } },
                    onToc = { showToc = true },
                    onSettings = { showSettings = true },
                    onListen = { viewModel.startListening(book, listen, chapterIndex, currentAnchor) },
                )
            }
        }

        if (showComments) {
            ReaderCommentsSheet(comments, onDismiss = { showComments = false })
        }

        if (showToc) {
            TocSheet(
                chapters = book.chapters,
                current = chapterIndex,
                onSelect = { index ->
                    showToc = false
                    if (index != chapterIndex) tocChapter = index
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

/** 本次阅读已经排好的页。layoutKey 变化才整段重排;平时只在两端接章。 */
private class ReadingPages {
    var layoutKey: String? = null
    var slots by mutableStateOf<List<StreamSlot>>(emptyList())
    var focus by mutableStateOf<Int?>(null)
}
