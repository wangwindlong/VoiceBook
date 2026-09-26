package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.screens.EmptyScreenContent

/**
 * 书城首页：继续阅读横条 + 书架网格（选定布局）。书架来自 calibre-web 的 OPDS
 * /opds/new feed，滚动到底自动翻页；搜索走 /opds/search。
 */
@Composable
fun LibraryScreen(
    navigateBack: () -> Unit,
    navigateToReader: (bookId: Int, title: String, author: String, coverUrl: String, downloadHref: String) -> Unit,
) {
    val viewModel = koinViewModel<LibraryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(state.server == null) }

    Column(
        Modifier
            .fillMaxSize()
            // 顶栏必须避开状态栏（Android 边到边模式）；桌面端该 insets 为 0，无影响。
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        TopBar(
            searching = state.searchResults != null,
            query = state.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            onSearch = viewModel::search,
            onClearSearch = viewModel::clearSearch,
            onSettings = { showSettings = true },
            onBack = navigateBack,
        )
        Spacer(Modifier.height(8.dp))

        when {
            state.server == null -> SetupCard { showSettings = true }

            state.searchResults != null -> SearchResultGrid(
                results = state.searchResults.orEmpty(),
                searching = state.searching,
                server = state.server!!,
                authHeader = viewModel.coverAuthHeader(state.server!!),
                viewModel = viewModel,
                onBookClick = { entry ->
                    navigateToReader(
                        entry.bookId, entry.title, entry.author,
                        viewModel.coverUrl(state.server!!, entry), entry.epubHref ?: "",
                    )
                },
            )

            else -> {
                if (state.history.isNotEmpty()) {
                    ContinueReadingRow(
                        history = state.history,
                        server = state.server!!,
                        authHeader = viewModel.coverAuthHeader(state.server!!),
                        onOpen = { entry ->
                            navigateToReader(entry.bookId, entry.title, entry.author, entry.coverUrl, "")
                        },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    state.books.isEmpty() -> Column(Modifier.fillMaxSize()) {
                        Text(
                            state.error ?: "书架是空的",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        EmptyScreenContent(Modifier.fillMaxWidth().weight(1f))
                    }

            else -> BookGrid(
                books = state.books,
                server = state.server!!,
                authHeader = viewModel.coverAuthHeader(state.server!!),
                viewModel = viewModel,
                loadingMore = state.loadingMore,
                endReached = state.endReached,
                onBookClick = { entry ->
                    navigateToReader(
                        entry.bookId, entry.title, entry.author,
                        viewModel.coverUrl(state.server!!, entry), entry.epubHref ?: "",
                    )
                },
            )
                }
            }
        }
        state.error?.takeIf { state.books.isNotEmpty() }?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    if (showSettings) {
        ServerSettingsDialog(
            initial = state.server,
            testing = state.testing,
            testResult = state.testResult,
            onTest = viewModel::testConnection,
            onSave = {
                viewModel.saveServer(it)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TopBar(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Spacer(Modifier.width(8.dp))
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
            Text("书城", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "服务器设置") }
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
private fun ContinueReadingRow(
    history: List<HistoryEntry>,
    server: us.wangxy.voicebook.reader.api.CalibreServer,
    authHeader: String?,
    onOpen: (HistoryEntry) -> Unit,
) {
    Column {
        Text("继续阅读", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history, key = { it.bookId }) { entry ->
                HistoryCard(entry, server, authHeader, onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    server: us.wangxy.voicebook.reader.api.CalibreServer,
    authHeader: String?,
    onClick: () -> Unit,
) {
    Card(Modifier.width(120.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BookCover(coverUrl = entry.coverUrl, authHeader = authHeader, title = entry.title)
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
private fun BookGrid(
    books: List<OpdsEntry>,
    server: us.wangxy.voicebook.reader.api.CalibreServer,
    authHeader: String?,
    viewModel: LibraryViewModel,
    loadingMore: Boolean,
    endReached: Boolean,
    onBookClick: (OpdsEntry) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Auto-load the next OPDS page when the user scrolls within sight of the bottom.
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(gridState, books.size, endReached, loadingMore) {
        snapshotFlow { nearEnd }.collect { near ->
            if (near && !endReached && !loadingMore) viewModel.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("书架", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(books, key = { it.bookId }) { entry ->
                BookCell(entry, viewModel.coverUrl(server, entry), authHeader, onClick = { onBookClick(entry) })
            }
            if (loadingMore) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            if (endReached && books.size > 6) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
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

@Composable
private fun BookCell(entry: OpdsEntry, coverUrl: String, authHeader: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BookCover(coverUrl = coverUrl, authHeader = authHeader, title = entry.title)
        Text(
            entry.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        Text(
            entry.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 封面：calibre-web 的 /opds/cover/{id}，带 Basic 认证头（若配置了账号）。 */
@Composable
private fun BookCover(coverUrl: String, authHeader: String?, title: String) {
    if (coverUrl.isEmpty()) {
        PlaceholderCover(title)
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
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun PlaceholderCover(title: String) {
    Box(
        Modifier
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
    server: us.wangxy.voicebook.reader.api.CalibreServer,
    authHeader: String?,
    viewModel: LibraryViewModel,
    onBookClick: (OpdsEntry) -> Unit,
) {
    when {
        searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results.isEmpty() -> EmptyScreenContent(Modifier.fillMaxSize())

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.bookId }) { entry ->
                BookCell(entry, viewModel.coverUrl(server, entry), authHeader, onClick = { onBookClick(entry) })
            }
        }
    }
}

@Composable
private fun ServerSettingsDialog(
    initial: us.wangxy.voicebook.reader.api.CalibreServer?,
    testing: Boolean,
    testResult: String?,
    onTest: (us.wangxy.voicebook.reader.api.CalibreServer) -> Unit,
    onSave: (us.wangxy.voicebook.reader.api.CalibreServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    val server = us.wangxy.voicebook.reader.api.CalibreServer(url.trim(), user.trim(), pass)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("calibre-web 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址（http://ip:端口）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名（需 OPDS/下载权限）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("密码") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onTest(server) }, enabled = url.isNotBlank() && !testing) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
                    }
                    testResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (it == "连接成功") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(server) },
                enabled = url.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
