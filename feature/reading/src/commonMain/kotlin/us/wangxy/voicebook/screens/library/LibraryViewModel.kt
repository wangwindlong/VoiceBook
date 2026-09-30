package us.wangxy.voicebook.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.data.theme.SeedColorExtractor

data class LibraryUiState(
    val server: CalibreServer? = null,
    /** True while a pull-to-refresh / first-load round-trip is in flight. */
    val refreshing: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<OpdsEntry>? = null,
    val searching: Boolean = false,
)

/**
 * 书城。书架列表走 Paging3 的缓存优先 [BookRepository]：冷启动直接渲染本地缓存，
 * 网络只补缺失的页。服务器设置在「我的」页；订阅 [BookRepository.serverVersion]，
 * 那边保存/切换服务器后这里自动重载。阅读历史归书架屏（ShelfViewModel）管。
 */
class LibraryViewModel(
    private val repository: BookRepository,
    private val api: CalibreWebApi,
    private val seedColorExtractor: SeedColorExtractor,
) : ViewModel() {

    private val uiState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = uiState.asStateFlow()

    /** Shelf load/search errors from the repository (null when healthy). */
    val error: StateFlow<String?> = repository.error

    /** Bumped after refresh/server change so the pager restarts from page 0. */
    private val refreshKey = MutableStateFlow(0)

    private val searchQuery = MutableStateFlow("")
    @OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
    val books: Flow<PagingData<CachedBook>> = kotlinx.coroutines.flow.combine(refreshKey,searchQuery.debounce(300)) { _,query -> query }
        .flatMapLatest { repository.filteredPager(it,"全部") }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            val server = repository.server()
            uiState.update { it.copy(server = server) }
            if (server != null) {
                refresh()
                backfillSeedColors()
            }
        }
        // 「我的」页保存/切换服务器后自动重载（drop(1)：初值由上面的 init 负责）。
        viewModelScope.launch {
            repository.serverVersion.drop(1).collect {
                val server = repository.server()
                uiState.update { it.copy(server = server, searchResults = null) }
                refresh()
            }
        }
    }

    /** Background: extract accent colors from covers for the dynamic theme. */
    private fun backfillSeedColors() {
        viewModelScope.launch { runCatching { seedColorExtractor.backfill() } }
    }

    fun refresh() {
        if (uiState.value.refreshing) return
        uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            repository.refresh()
            uiState.update { it.copy(refreshing = false) }
            refreshKey.value++
            backfillSeedColors()
        }
    }

    fun setSearchQuery(query: String) { uiState.update { it.copy(searchQuery = query) }; searchQuery.value=query }

    fun search() { searchQuery.value=uiState.value.searchQuery.trim() }
    fun clearSearch() { setSearchQuery("") }

    fun coverAuthHeader(server: CalibreServer): String? = repository.coverAuthHeader(server)

    /** Absolute cover URL for an OPDS entry (search results aren't cached). */
    fun resolveCoverUrl(server: CalibreServer, entry: OpdsEntry): String = api.coverUrl(server, entry)
}
