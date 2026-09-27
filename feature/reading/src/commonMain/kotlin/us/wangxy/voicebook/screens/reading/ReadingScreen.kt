package us.wangxy.voicebook.screens.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import us.wangxy.voicebook.screens.library.LibraryScreen
import us.wangxy.voicebook.screens.shelf.ShelfScreen
import us.wangxy.voicebook.twine.TwineSegmented

/**
 * 阅读 tab 容器:顶部胶囊分段选择器(书架/书城,书架为默认页)+ 内层 HorizontalPager。
 * pagerState 由 App 壳创建并持久化选中页;内层滑到边界后剩余手势经
 * 嵌套滚动协议传给外层底部 tab pager,实现链式切换。
 */
@Composable
fun ReadingScreen(
    pagerState: PagerState,
    onOpenBook: (bookId: Int, title: String, author: String, coverUrl: String, downloadHref: String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // iOS 式胶囊分段:居中、不占满全屏
        TwineSegmented(
            labels = listOf("书架", "书城"),
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentSize(Alignment.Center)
                .widthIn(max = 280.dp)
                .padding(top = 6.dp),
        )
        Spacer(Modifier.padding(top = 4.dp))
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> ShelfScreen(
                    onOpenBook = { bookId, title, author, coverUrl ->
                        onOpenBook(bookId, title, author, coverUrl, "")
                    },
                    onSeedColorChange = onSeedColorChange,
                    onOpenSidebar = onOpenSidebar,
                )
                else -> LibraryScreen(
                    onOpenBook = onOpenBook,
                    onSeedColorChange = onSeedColorChange,
                    onOpenSettings = onOpenSettings,
                    onOpenSidebar = onOpenSidebar,
                )
            }
        }
    }
}
