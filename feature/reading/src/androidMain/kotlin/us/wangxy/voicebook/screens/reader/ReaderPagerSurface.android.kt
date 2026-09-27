@file:OptIn(eu.wewox.pagecurl.ExperimentalPageCurlApi::class)

package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState
import kotlinx.coroutines.launch
import us.wangxy.voicebook.theme.LocalTwineTokens

/**
 * Android 实现:pagecurl 卷页。点按翻页由外层统一处理(点击区语义保持与
 * HorizontalPager 版一致),pagecurl 自带的 TargetTapInteraction 置零避免
 * 一次点击翻两页;纸背色与卷页阴影取 Twine 令牌。
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
    val tokens = LocalTwineTokens.current
    val scope = rememberCoroutineScope()
    val state = rememberPageCurlState(initialPage)

    LaunchedEffect(state) {
        snapshotFlow { state.current }.collect { onSettledPage(it) }
    }
    LaunchedEffect(jumpToPage) {
        if (state.current != jumpToPage) state.snapTo(jumpToPage)
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
            // 点按翻页全部关闭,点击区语义由外层 detectTapGestures 统一定义
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
        modifier.pointerInput(pageCount) {
            detectTapGestures { offset ->
                when {
                    offset.x < size.width / 3f -> {
                        if (state.current > 0) scope.launch { state.prev() } else onLeftEdgeTap()
                    }

                    offset.x > size.width * 2f / 3f -> {
                        if (state.current < pageCount - 1) scope.launch { state.next() } else onRightEdgeTap()
                    }

                    else -> onCenterTap()
                }
            }
        },
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
