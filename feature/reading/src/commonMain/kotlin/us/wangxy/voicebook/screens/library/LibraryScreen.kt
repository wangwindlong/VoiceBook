package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.window.core.layout.WindowWidthSizeClass
import org.koin.compose.viewmodel.koinViewModel

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
        Spacer(Modifier.height(12.dp))
        LibraryTopBar(
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
        Spacer(Modifier.height(8.dp))

        val server = state.server
        when {
            server == null -> LibrarySetupCard(onOpenSettings)

            state.searchResults != null -> LibrarySearchGrid(
                results = state.searchResults.orEmpty(),
                searching = state.searching,
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

            else -> LibraryShelf(
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
