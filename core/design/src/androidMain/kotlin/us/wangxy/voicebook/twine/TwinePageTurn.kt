@file:OptIn(eu.wewox.pagecurl.ExperimentalPageCurlApi::class)

package us.wangxy.voicebook.twine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import eu.wewox.pagecurl.page.rememberPageCurlState
import us.wangxy.voicebook.theme.LocalTwineTokens

/**
 * 纸页翻页容器:用 pagecurl 的手势卷页 + Twine 令牌的纸面衬底。
 * pagecurl 1.5.1 只发布 Android 工件,故本组件仅在 androidMain;桌面/iOS/Web
 * 需要自写卷页实现或等上游 KMP 化。
 *
 * 页内容通过 [page] 按 index 提供,纸面底色与左右留白由令牌决定;
 * 编程翻页用 [state.next()] / [state.previous()]。
 */
@Composable
fun TwinePageTurn(
    count: Int,
    modifier: Modifier = Modifier,
    state: PageCurlState = rememberPageCurlState(),
    page: @Composable (Int) -> Unit,
) {
    val tokens = LocalTwineTokens.current
    PageCurl(
        count = count,
        state = state,
        modifier = modifier,
    ) { index ->
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.paper)
                .padding(horizontal = tokens.pageGutter, vertical = 24.dp),
        ) {
            page(index)
        }
    }
}
