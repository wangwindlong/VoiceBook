package us.wangxy.voicebook.screens.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.compose.LocalPlatformContext
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.screens.EmptyScreenContent

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
        books.loadState.refresh is androidx.paging.LoadState.Loading && books.itemCount == 0 &&
            history.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        books.itemCount == 0 && history.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("书架", style = MaterialTheme.typography.titleLarge)
            Text(
                errorMessage ?: "书架是空的，去书城添加一本书吧",
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                GridBookCell(
                    book = book,
                    authHeader = authHeader,
                    onClick = {
                        onSeedColorChange(book.seedColor)
                        onOpenBook(book.bookId, book.title, book.author, book.coverUrl)
                    },
                )
            }
            if (books.loadState.append is androidx.paging.LoadState.Loading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            if (books.loadState.append is androidx.paging.LoadState.NotLoading &&
                books.loadState.append.endOfPaginationReached && books.itemCount > 6
            ) {
                item(key = "end", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "— 到底了 —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    authHeader: String?,
    onClick: () -> Unit,
) {
    Card(Modifier.width(120.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShelfCover(coverUrl = entry.coverUrl, title = entry.title, authHeader = authHeader, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            Text(
                entry.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { entry.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "已读 ${entry.progress}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GridBookCell(book: CachedBook, authHeader: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShelfCover(coverUrl = book.coverUrl, title = book.title, authHeader = authHeader, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Text(
            book.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        Text(
            book.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 封面：带 Basic 认证头（若配置了账号）。 */
@Composable
private fun ShelfCover(coverUrl: String, title: String, authHeader: String?, modifier: Modifier) {
    if (coverUrl.isEmpty()) {
        Box(
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(2), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(coverUrl)
            .crossfade(true)
            .httpHeaders(
                NetworkHeaders.Builder().apply { authHeader?.let { set("Authorization", it) } }.build(),
            )
            .build(),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
