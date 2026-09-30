package us.wangxy.voicebook.screens.reading

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.filled.MoreVert
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.screens.shelf.ShelfViewModel
import us.wangxy.voicebook.screens.reader.ContentComments
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import us.wangxy.voicebook.screens.library.LibraryScreen
import us.wangxy.voicebook.screens.shelf.ShelfScreen
import us.wangxy.voicebook.twine.TwineSegmented

/**
 * 阅读页面：书架/书城分段选择器与内层分页。
 */
@Composable
fun ReadingScreen(
    pagerState: PagerState,
    onOpenBook: (bookId: Int, title: String, author: String, coverUrl: String, downloadHref: String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    flingBehavior: TargetedFlingBehavior = PagerDefaults.flingBehavior(pagerState),
    pageNestedScrollConnection: NestedScrollConnection =
        PagerDefaults.pageNestedScrollConnection(pagerState, Orientation.Horizontal),
) {
    val scope = rememberCoroutineScope()
    val shelf = koinViewModel<ShelfViewModel>()
    val history by shelf.history.collectAsStateWithLifecycle()
    var showComments by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), flingBehavior = flingBehavior,
            pageNestedScrollConnection = pageNestedScrollConnection) { page ->
            when (page) {
                0 -> ShelfScreen(onOpenBook = onOpenBook,
                    onSeedColorChange = onSeedColorChange, onOpenSidebar = onOpenSidebar,
                    onBrowseLibrary = { scope.launch { pagerState.animateScrollToPage(1) } })
                else -> LibraryScreen(onOpenBook = onOpenBook, onSeedColorChange = onSeedColorChange,
                    onOpenSettings = onOpenSettings, onOpenSidebar = onOpenSidebar)
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp)) }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            NavigationBarItem(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("书架") })
            NavigationBarItem(selected = false, onClick = {
                val book = history.firstOrNull()
                if (book == null) hint = "先从书架选择一本书开始阅读"
                else onOpenBook(book.bookId, book.title, book.author, book.coverUrl, "")
            }, icon = { Icon(Icons.Outlined.AutoStories, null) }, label = { Text("阅读") })
            NavigationBarItem(selected = showComments, onClick = {
                if (history.isEmpty()) hint = "先选择一本书，再查看本书评论" else showComments = true
            }, icon = { Icon(Icons.Outlined.Comment, null) }, label = { Text("评论") })
            NavigationBarItem(selected = false, onClick = onOpenSidebar, icon = { Icon(Icons.Default.MoreVert, null) }, label = { Text("更多") })
        }
    }
    if (showComments) history.firstOrNull()?.let { book -> ContentComments("/calibre/book/${book.bookId}", book.title) { showComments = false } }
}
