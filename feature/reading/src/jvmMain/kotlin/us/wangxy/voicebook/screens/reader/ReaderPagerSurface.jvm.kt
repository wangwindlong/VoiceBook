package us.wangxy.voicebook.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import us.wangxy.voicebook.reader.store.ReaderPageTurn

/** 桌面实现:平移翻页(共用实现);卷页待自写 Twine 翻页后接入。 */
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
    pageKey: (Int) -> Any,
    page: @Composable (Int) -> Unit,
) {
    SlidePagerSurface(
        pageCount = pageCount,
        initialPage = initialPage,
        jumpToPage = jumpToPage,
        chromeVisible = chromeVisible,
        modifier = modifier,
        onSettledPage = onSettledPage,
        onLeftEdgeTap = onLeftEdgeTap,
        onRightEdgeTap = onRightEdgeTap,
        onCenterTap = onCenterTap,
        waitForDoubleTap = waitForDoubleTap,
        pageKey = pageKey,
        page = page,
    )
}

internal actual val SupportsCurlPageTurn: Boolean = false
