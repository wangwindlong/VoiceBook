package us.wangxy.voicebook.screens.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.screens.EmptyScreenContent
import us.wangxy.voicebook.screens.book.BookCell
import us.wangxy.voicebook.screens.book.pagingAppendRows
import us.wangxy.voicebook.ui.widget.CenteredProgress

/**
 * 书架：继续阅读（历史，最新在前）+ 全部书籍（缓存优先分页网格，与书城同源）。
 * 历史条目点击从上次位置续读；网格点击直接打开。
 */
@Composable
fun ShelfScreen(
    onOpenBook: (bookId: Int, title: String, author: String, coverUrl: String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSidebar: () -> Unit = {},
) {
    val viewModel = koinViewModel<ShelfViewModel>()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val errorMessage by viewModel.error.collectAsStateWithLifecycle()
    val books = viewModel.books.collectAsLazyPagingItems()
    val authHeader = server?.let { viewModel.coverAuthHeader(it) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Filled.Build, contentDescription = "工具箱")
            }
        }
        when {
            books.loadState.refresh is LoadState.Loading && books.itemCount == 0 && history.isEmpty() ->
                CenteredProgress(Modifier.fillMaxSize())

            books.itemCount == 0 && history.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Text("书架", style = MaterialTheme.typography.titleLarge)
                Text(
                    errorMessage ?: "书架是空的，去书城添加一本书吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                EmptyScreenContent(Modifier.fillMaxWidth())
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (history.isNotEmpty()) {
                    item(key = "history_header", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("继续阅读", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(history, key = { it.bookId }) { entry ->
                                    HistoryCard(
                                        entry = entry,
                                        authHeader = authHeader,
                                        onClick = {
                                            onSeedColorChange(entry.seedColor)
                                            onOpenBook(entry.bookId, entry.title, entry.author, entry.coverUrl)
                                        },
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                item(key = "all_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text("全部书籍", style = MaterialTheme.typography.titleMedium)
                }
                items(
                    count = books.itemCount,
                    key = books.itemKey { "book_${it.bookId}" },
                    span = { GridItemSpan(1) },
                ) { index ->
                    val book = books[index] ?: return@items
                    BookCell(
                        title = book.title,
                        author = book.author,
                        coverUrl = book.coverUrl,
                        authHeader = authHeader,
                        placeholderChars = 2,
                        placeholderColor = LocalContentColor.current,
                        onClick = {
                            onSeedColorChange(book.seedColor)
                            onOpenBook(book.bookId, book.title, book.author, book.coverUrl)
                        },
                    )
                }
                pagingAppendRows(
                    append = books.loadState.append,
                    itemCount = books.itemCount,
                    loadingKey = "loading",
                    endKey = "end",
                )
            }
        }
    }
}
