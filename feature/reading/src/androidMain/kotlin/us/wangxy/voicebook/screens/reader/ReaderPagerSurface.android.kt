@file:OptIn(eu.wewox.pagecurl.ExperimentalPageCurlApi::class)

package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState
import kotlinx.coroutines.launch
import us.wangxy.voicebook.reader.store.ReaderPageTurn
import us.wangxy.voicebook.theme.LocalTwineTokens

/**
 * Android 实现:按设置在 pagecurl 卷页与 HorizontalPager 平移间切换。点按翻页由
 * 外层 readerTapZones 统一处理(Initial pass 抢事件),pagecurl 自带的
 * TargetTapInteraction 置零作为兜底;纸背色与卷页阴影取 Twine 令牌。
 */
@Composable
internal actual fun ReaderPagerSurface(
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
    page: @Composable (Int) -> Unit,
) {
    when (pageTurn) {
        ReaderPageTurn.Curl -> CurlPagerSurface(
            pageCount, initialPage, jumpToPage, chromeVisible, modifier,
            onSettledPage, onLeftEdgeTap, onRightEdgeTap, onCenterTap, waitForDoubleTap, page,
        )

        ReaderPageTurn.Slide -> SlidePagerSurface(
            pageCount, initialPage, jumpToPage, chromeVisible, modifier,
            onSettledPage, onLeftEdgeTap, onRightEdgeTap, onCenterTap, waitForDoubleTap, page,
        )
    }
}

@Composable
private fun CurlPagerSurface(
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
    page: @Composable (Int) -> Unit,
) {
    val tokens = LocalTwineTokens.current
    val scope = rememberCoroutineScope()
    val state = rememberPageCurlState(initialPage)

    LaunchedEffect(state) {
        snapshotFlow { state.current }.collect { onSettledPage(it) }
    }
    LaunchedEffect(jumpToPage) {
        val target = jumpToPage ?: return@LaunchedEffect
        if (state.current != target) state.snapTo(target)
    }
    val config = remember(tokens) {
        PageCurlConfig(
            backPageColor = tokens.paper,
            backPageContentAlpha = 0.55f,
            shadowColor = tokens.ink,
            shadowAlpha = 0.30f,
            shadowRadius = 24.dp,
            shadowOffset = DpOffset(x = 0.dp, y = 8.dp),
            dragForwardEnabled = true,
            dragBackwardEnabled = true,
            // 点按翻页全部由外层 readerTapZones 定义,pagecurl 自带点按置零兜底
            tapForwardEnabled = false,
            tapBackwardEnabled = false,
            tapCustomEnabled = false,
            dragInteraction = PageCurlConfig.GestureDragInteraction(),
            tapInteraction = PageCurlConfig.TargetTapInteraction(
                forward = PageCurlConfig.TargetTapInteraction.Config(target = Rect.Zero),
                backward = PageCurlConfig.TargetTapInteraction.Config(target = Rect.Zero),
            ),
            onCustomTap = { _, _ -> false },
        )
    }

    Box(
        modifier.readerTapZones(
            chromeVisible = chromeVisible,
            canGoPrev = { state.current > 0 },
            canGoNext = { state.current < pageCount - 1 },
            goPrev = { scope.launch { state.prev() } },
            goNext = { scope.launch { state.next() } },
            onLeftEdgeTap = onLeftEdgeTap,
            onRightEdgeTap = onRightEdgeTap,
            onCenterTap = onCenterTap,
            waitForDoubleTap = waitForDoubleTap,
        ),
    ) {
        PageCurl(
            count = pageCount,
            state = state,
            config = config,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            page(index)
        }
    }
}

internal actual val SupportsCurlPageTurn: Boolean = true
