package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.screens.EmptyScreenContent
import us.wangxy.voicebook.screens.book.BookCell
import us.wangxy.voicebook.screens.book.BookCover
import us.wangxy.voicebook.screens.book.pagingAppendRows
import us.wangxy.voicebook.ui.widget.CenteredProgress

/** 书架主体：宽屏 list-detail 双栏，窄屏单栏网格。 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun LibraryShelf(
    books: LazyPagingItems<CachedBook>,
    authHeader: String?,
    emptyHint: String,
    twoPane: Boolean,
    onOpen: (bookId: Int, title: String, author: String, coverUrl: String, downloadHref: String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val navigator = rememberListDetailPaneScaffoldNavigator<CachedBook>()

    val openBook: (CachedBook) -> Unit = { book ->
        onSeedColorChange(book.seedColor)
        onOpen(book.bookId, book.title, book.author, book.coverUrl, book.epubHref)
    }

    if (!twoPane) {
        ShelfGrid(
            books = books,
            authHeader = authHeader,
            emptyHint = emptyHint,
            onBookClick = openBook,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            ShelfGrid(
                books = books,
                authHeader = authHeader,
                emptyHint = emptyHint,
                onBookClick = { book ->
                    onSeedColorChange(book.seedColor)
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, book) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
        detailPane = {
            val selected = navigator.currentDestination?.contentKey
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "选择一本书查看详情",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BookDetailPane(
                    book = selected,
                    onRead = { openBook(selected) },
                )
            }
        },
    )
}

/** 分页书架网格：占位加载、到底提示，滚动到尾部自动触发下一页取数。 */
@Composable
private fun ShelfGrid(
    books: LazyPagingItems<CachedBook>,
    authHeader: String?,
    emptyHint: String,
    onBookClick: (CachedBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        books.loadState.refresh is LoadState.Loading && books.itemCount == 0 ->
            CenteredProgress(modifier.fillMaxSize())

        books.itemCount == 0 -> Column(modifier.fillMaxSize()) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            EmptyScreenContent(Modifier.fillMaxWidth())
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            state = rememberLazyGridState(),
            modifier = modifier,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                count = books.itemCount,
                key = books.itemKey { it.bookId },
            ) { index ->
                val book = books[index] ?: return@items
                BookCell(
                    title = book.title,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    authHeader = authHeader,
                    onClick = { onBookClick(book) },
                )
            }
            pagingAppendRows(books.loadState.append, books.itemCount)
        }
    }
}

/** 宽屏右栏：封面 + 元信息 + 开始阅读。 */
@Composable
private fun BookDetailPane(book: CachedBook, onRead: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(
            coverUrl = book.coverUrl,
            title = book.title,
            authHeader = null,
            modifier = Modifier.width(180.dp),
        )
        Text(book.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            book.author,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRead) {
            Text("开始阅读")
        }
    }
}
