package us.wangxy.voicebook.screens.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.screens.EmptyScreenContent

/**
 * 书城（应用首页）。窄屏单栏：网格直接点开阅读；宽屏（非 COMPACT）list-detail 双栏，
 * 点书在右栏预览再进入阅读。书架数据来自 [LibraryViewModel.books] 的 Paging3 流，
 * 本地缓存优先，滚动到底自动按 OPDS 页序取数。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (bookId: Int, title: String, author: String, coverUrl: String, downloadHref: String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
) {
    val viewModel = koinViewModel<LibraryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorMessage by viewModel.error.collectAsStateWithLifecycle()
    val books = viewModel.books.collectAsLazyPagingItems()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        TopBar(
            searching = state.searchResults != null,
            query = state.searchQuery,
            refreshing = state.refreshing,
            onQueryChange = viewModel::setSearchQuery,
            onSearch = viewModel::search,
            onClearSearch = viewModel::clearSearch,
            onRefresh = viewModel::refresh,
            onOpenSidebar = onOpenSidebar,
        )
        if (state.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

        val server = state.server
        when {
            server == null -> SetupCard(onOpenSettings)

            state.searchResults != null -> SearchResultGrid(
                results = state.searchResults.orEmpty(),
                searching = state.searching,
                server = server,
                authHeader = viewModel.coverAuthHeader(server),
                coverUrl = { entry -> viewModel.resolveCoverUrl(server, entry) },
                onOpen = { entry ->
                    onOpenBook(
                        entry.bookId, entry.title, entry.author,
                        viewModel.resolveCoverUrl(server, entry),
                        entry.epubHref ?: "",
                    )
                },
            )

            else -> ShelfContent(
                books = books,
                authHeader = viewModel.coverAuthHeader(server),
                emptyHint = errorMessage ?: "书架是空的",
                twoPane = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass !=
                    WindowWidthSizeClass.COMPACT,
                onOpen = onOpenBook,
                onSeedColorChange = onSeedColorChange,
            )
        }
    }

}

@Composable
private fun TopBar(
    searching: Boolean,
    query: String,
    refreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索书名或作者") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "搜索") }
                },
            )
            IconButton(onClick = onClearSearch) { Icon(Icons.Filled.Close, "取消搜索") }
        } else {
            IconButton(onClick = onOpenSidebar) { Icon(Icons.Filled.Build, contentDescription = "工具箱") }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "刷新书架") }
        }
    }
}

/** 书架主体：宽屏 list-detail 双栏，窄屏单栏网格。 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ShelfContent(
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
        books.loadState.refresh is androidx.paging.LoadState.Loading && books.itemCount == 0 ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

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
            state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
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
                BookCell(book, authHeader, onClick = { onBookClick(book) })
            }
            if (books.loadState.append is androidx.paging.LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            if (books.loadState.append is androidx.paging.LoadState.NotLoading &&
                books.loadState.append.endOfPaginationReached && books.itemCount > 6
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "— 到底了 —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
        BookCover(coverUrl = book.coverUrl, authHeader = null, title = book.title, modifier = Modifier.width(180.dp))
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

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接 calibre-web 服务器", style = MaterialTheme.typography.titleMedium)
            Text(
                "填入服务器地址（如 http://192.168.1.10:8083）和开启 OPDS 权限的账号，即可浏览书架并阅读 EPUB。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("去配置") }
        }
    }
}

@Composable
private fun BookCell(book: CachedBook, authHeader: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BookCover(coverUrl = book.coverUrl, authHeader = authHeader, title = book.title)
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

/** 封面：calibre-web 的 /opds/cover/{id}，带 Basic 认证头（若配置了账号）。 */
@Composable
private fun BookCover(coverUrl: String, authHeader: String?, title: String, modifier: Modifier = Modifier) {
    if (coverUrl.isEmpty()) {
        PlaceholderCover(title, modifier)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
            .data(coverUrl)
            .crossfade(true)
            .httpHeaders(
                NetworkHeaders.Builder().apply { authHeader?.let { set("Authorization", it) } }.build(),
            )
            .build(),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun PlaceholderCover(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(4),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultGrid(
    results: List<OpdsEntry>,
    searching: Boolean,
    server: CalibreServer,
    authHeader: String?,
    coverUrl: (OpdsEntry) -> String,
    onOpen: (OpdsEntry) -> Unit,
) {
    when {
        searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results.isEmpty() -> EmptyScreenContent(Modifier.fillMaxSize())

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.bookId }) { entry ->
                val book = CachedBook(
                    bookId = entry.bookId,
                    title = entry.title,
                    author = entry.author,
                    coverUrl = coverUrl(entry),
                    epubHref = entry.epubHref ?: "",
                    pos = 0,
                )
                BookCell(book, authHeader, onClick = { onOpen(entry) })
            }
        }
    }
}
