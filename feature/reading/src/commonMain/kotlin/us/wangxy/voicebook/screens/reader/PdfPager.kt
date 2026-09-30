package us.wangxy.voicebook.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.reader.pdf.PdfDocument
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.theme.LocalTwineTokens

/** 缩放后重渲染请求位图的长边上限(px),防止 4x 放大在长页上撑爆内存。 */
private const val MaxRenderEdgePx = 4096f

/**
 * PDF 阅读模式：各平台引擎把页面渲染成位图，HorizontalPager 翻页。位置即页码
 * （持久化在 history 的 spineIndex），进入时恢复到上次页。
 */
@Composable
internal fun PdfPager(
    pdf: PdfDocument,
    startPage: Int,
    stateController: ReaderStateController,
    viewModel: ReaderViewModel,
    title: String,
    /** 该书评论总数，透传给顶栏角标；由宿主页面的评论区控制器提供。 */
    commentCount: Int,
    /** 点击顶栏评论入口；宿主页面负责打开评论抽屉。 */
    onOpenComments: () -> Unit,
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
            if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
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
            // 档位只量化手势倍率。适配系数是 px/pt,乘进去会让落定页落到 2x/3x,
            // 和邻页预渲染的 1x 缓存错档,每翻一页都先清空成 loading。
            // 高档未就绪时继续显示已有位图,不把整页换成转圈。
            val isSettled = pageIndex == settledPage
            val bucket = if (isSettled) pdfRenderBucket(zoom.scale) else PdfBitmapCache.BaseBucket
            var bitmap by remember(pageIndex) {
                mutableStateOf(cache.peek(pageIndex, bucket) ?: cache.best(pageIndex))
            }
            LaunchedEffect(pageIndex, bucket, widthPx, heightPx) {
                if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
                cache.peek(pageIndex, bucket)?.let {
                    bitmap = it
                    return@LaunchedEffect
                }
                if (bitmap == null) cache.best(pageIndex)?.let { bitmap = it }
                // 请求尺寸 = 视口 × 档位,长边钳到 4096(平台 MaxRenderScale=3 仍是硬上限)
                val renderScale = minOf(bucket, MaxRenderEdgePx / maxOf(widthPx, heightPx).coerceAtLeast(1))
                val rendered = withContext(Dispatchers.Default) {
                    pdf.renderPage(pageIndex, (widthPx * renderScale).toInt(), (heightPx * renderScale).toInt())
                }
                if (rendered != null) {
                    cache.put(pageIndex, bucket, rendered)
                    bitmap = rendered
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tokens.paper)
                    .pdfZoomable(zoom, zoomScope, contentW, contentH, viewportW, viewportH),
                contentAlignment = Alignment.Center,
            ) {
                val frame = bitmap
                if (frame == null) {
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
                            bitmap = frame,
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
                onComments = {
                    showChrome = false
                    onOpenComments()
                },
                commentCount = commentCount,
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

    /** 该页已有的最清晰一帧;目标档还在渲染时用来顶上,避免画面被清空。 */
    fun best(page: Int): ImageBitmap? {
        val key = entries.keys.filter { it.page == page }.maxByOrNull { it.bucket } ?: return null
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

