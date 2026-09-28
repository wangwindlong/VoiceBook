package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.wangxy.voicebook.reader.store.ReaderPageTurn

internal enum class ReaderTapZone { Left, Right, Center }

/** 当前平台是否支持卷页(Android pagecurl);不支持时设置面板隐藏翻页方式行。 */
internal expect val SupportsCurlPageTurn: Boolean

/**
 * chrome 展开(顶/底栏可见)时,顶/底栏两条让位带高度:带内点击交给栏内控件
 * (返回/Aa/目录/进度条),不触发翻页区。
 */
private val ChromeTopBand = 64.dp
private val ChromeBottomBand = 112.dp

/**
 * 章首/章末的横向手势该翻向哪一章。卷页和平移在边界会关掉继续翻页,
 * 同一次拖拽要在这里补上跨章;章内翻页仍交给 Pager / PageCurl。
 * 返回 null 表示这次手势不是跨章翻页。
 */
internal enum class BoundaryTurn { Previous, Next }

internal fun boundaryChapterTurn(
    atStart: Boolean,
    atEnd: Boolean,
    dx: Float,
    dy: Float,
    velocityX: Float,
    velocityY: Float,
    minDragPx: Float,
    minFlingVelocity: Float,
): BoundaryTurn? {
    val flung = abs(velocityX) >= minFlingVelocity && abs(velocityX) > abs(velocityY)
    val dragged = abs(dx) >= minDragPx && abs(dx) > abs(dy)
    if (!flung && !dragged) return null
    val forward = if (flung) velocityX < 0f else dx < 0f
    return when {
        forward && atEnd -> BoundaryTurn.Next
        !forward && atStart -> BoundaryTurn.Previous
        else -> null
    }
}

/**
 * 阅读页点击区:左 1/3 上一页、右 1/3 下一页、中间切换 chrome;到达首页/末页
 * 再点击,或在该方向上滑过一截/甩动,经 onLeftEdgeTap/onRightEdgeTap 交回调用方(跨章节)。
 *
 * 检测跑在 Initial pass(父先于子,先于 PageCurl 内部手势与一切 Main pass 处理器),
 * 且全程不消费事件:拖拽翻页(PageCurl drag / Pager 滚动)与栏内控件拿到的是
 * 完全原生的按下事件,不会被饿死;PageCurl 自带的 tap 手势目标已全部置零,
 * 即便它消费了 down 也不会产生动作,所以这里无需靠消费来去重。
 * chrome 展开时:顶/底栏让位带内的按下不处理(交给栏内控件),点内容收起 chrome。
 */
internal fun Modifier.readerTapZones(
    chromeVisible: Boolean,
    canGoPrev: () -> Boolean,
    canGoNext: () -> Boolean,
    goPrev: () -> Unit,
    goNext: () -> Unit,
    onLeftEdgeTap: () -> Unit,
    onRightEdgeTap: () -> Unit,
    onCenterTap: () -> Unit,
    waitForDoubleTap: Boolean = false,
): Modifier = pointerInput(chromeVisible, waitForDoubleTap) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val topBand = ChromeTopBand.toPx()
        val bottomBand = ChromeBottomBand.toPx()
        if (chromeVisible &&
            (down.position.y < topBand || down.position.y > size.height - bottomBand)
        ) {
            // 栏内控件自行响应,这里不处理也不消费
            return@awaitEachGesture
        }
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        // 按下时是否顶到章界。章内的同一次滑动会改 current,不能拖完再判断。
        // 这里不消费事件:章内翻页、PDF 放大后的平移仍由子层处理。
        val atStart = !canGoPrev()
        val atEnd = !canGoNext()
        var pointer = down
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            pointer = change
            if (!change.pressed) break
        }
        val dx = pointer.position.x - down.position.x
        val dy = pointer.position.y - down.position.y
        if ((down.position - pointer.position).getDistance() > viewConfiguration.touchSlop) {
            val velocity = velocityTracker.calculateVelocity()
            when (
                boundaryChapterTurn(
                    atStart = atStart,
                    atEnd = atEnd,
                    dx = dx,
                    dy = dy,
                    velocityX = velocity.x,
                    velocityY = velocity.y,
                    minDragPx = viewConfiguration.touchSlop * 3f,
                    minFlingVelocity = viewConfiguration.minimumFlingVelocity,
                )
            ) {
                BoundaryTurn.Previous -> onLeftEdgeTap()
                BoundaryTurn.Next -> onRightEdgeTap()
                null -> Unit
            }
            return@awaitEachGesture
        }
        val up = pointer
        if (waitForDoubleTap) {
            // PDF 双击缩放让路:单击延迟一个双击窗口再触发;窗口内来了第二击,
            // 两击的动作都吞掉(翻页/chrome 交给缩放层处理),避免双击误翻页
            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            }
            if (secondDown != null) {
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                return@awaitEachGesture
            }
        }
        if (chromeVisible) {
            onCenterTap()
            return@awaitEachGesture
        }
        when {
            up.position.x < size.width / 3f -> if (canGoPrev()) goPrev() else onLeftEdgeTap()
            up.position.x > size.width * 2f / 3f -> if (canGoNext()) goNext() else onRightEdgeTap()
            else -> onCenterTap()
        }
    }
}

/**
 * 阅读翻页面板抽象:[ReaderPageTurn.Curl] 用 pagecurl 卷页(仅 Android),
 * [ReaderPageTurn.Slide] 用 HorizontalPager 平移。
 *
 * [onSettledPage] 在翻页落定后上报当前页码(调用方据此持久化进度);
 * [jumpToPage] 非空时无动画跳页(章节切换/字号重排后的位置恢复/进度条/自动阅读),
 * 为 null 表示无跳页请求——落定后的页码绝不能回灌成跳页目标,否则与卷页滞后的
 * current 回报互相拉扯,会把刚翻到的页又拽回去;[chromeVisible] 顶/底栏是否展开
 * (影响点击区让位,见 [readerTapZones])。
 */
@Composable
internal expect fun ReaderPagerSurface(
    pageCount: Int,
    initialPage: Int,
    jumpToPage: Int?,
    pageTurn: ReaderPageTurn,
    chromeVisible: Boolean,
    modifier: Modifier,
    onSettledPage: (Int) -> Unit,
    onLeftEdgeTap: () -> Unit,
    onRightEdgeTap: () -> Unit,
    onCenterTap: () -> Unit,
    waitForDoubleTap: Boolean,
    pageKey: (Int) -> Any = { it },
    page: @Composable (Int) -> Unit,
)

/**
 * 平移翻页的共用实现(桌面/iOS/Web 全平台 + Android 的平移模式):
 * HorizontalPager + 三分点击区。
 */
@Composable
internal fun SlidePagerSurface(
    pageCount: Int,
    initialPage: Int,
    jumpToPage: Int?,
    chromeVisible: Boolean,
    modifier: Modifier,
    onSettledPage: (Int) -> Unit,
    onLeftEdgeTap: () -> Unit,
    onRightEdgeTap: () -> Unit,
    onCenterTap: () -> Unit,
    waitForDoubleTap: Boolean,
    pageKey: (Int) -> Any,
    page: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }
    val onSettled by rememberUpdatedState(onSettledPage)
    // 只在目标变化时瞬间对齐(换章后停在同一页)。每次重组都滚会把用户翻到的页拽回去。
    var appliedJump by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onSettled(it) }
    }
    SideEffect {
        val target = jumpToPage
        if (target == null) {
            if (appliedJump != null) appliedJump = null
            return@SideEffect
        }
        if (target == appliedJump) return@SideEffect
        if (pageCount <= 0 || target !in 0 until pageCount) return@SideEffect
        appliedJump = target
        if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
            pagerState.requestScrollToPage(target)
        } else {
            onSettled(target)
        }
    }

    HorizontalPager(
        state = pagerState,
        key = pageKey,
        modifier = modifier.readerTapZones(
            chromeVisible = chromeVisible,
            canGoPrev = { pagerState.currentPage > 0 },
            canGoNext = { pagerState.currentPage < pagerState.pageCount - 1 },
            goPrev = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            goNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            onLeftEdgeTap = onLeftEdgeTap,
            onRightEdgeTap = onRightEdgeTap,
            onCenterTap = onCenterTap,
            waitForDoubleTap = waitForDoubleTap,
        ),
    ) { index ->
        page(index)
    }
}
