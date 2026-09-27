package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Desktop 实现:HorizontalPager + 与 Android 版一致的点击区语义。
 * (iOS/Web 同一份实现,见各自源集;待自写卷页后统一换 Twine 翻页。)
 */
@Composable
internal actual fun ReaderPagerSurface(
    pageCount: Int,
    initialPage: Int,
    jumpToPage: Int,
    modifier: Modifier,
    onSettledPage: (Int) -> Unit,
    onLeftEdgeTap: () -> Unit,
    onRightEdgeTap: () -> Unit,
    onCenterTap: () -> Unit,
    page: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onSettledPage(it) }
    }
    LaunchedEffect(jumpToPage) {
        pagerState.scrollToPage(jumpToPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.pointerInput(pageCount) {
            detectTapGestures { offset ->
                when {
                    offset.x < size.width / 3f -> {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else onLeftEdgeTap()
                    }

                    offset.x > size.width * 2f / 3f -> {
                        if (pagerState.currentPage < pageCount - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else onRightEdgeTap()
                    }

                    else -> onCenterTap()
                }
            }
        },
    ) { index ->
        page(index)
    }
}
