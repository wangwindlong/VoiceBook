package us.wangxy.voicebook.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.reader.store.ReaderStateController

data class LibraryUiState(
    val server: CalibreServer? = null,
    val books: List<OpdsEntry> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<OpdsEntry>? = null,
    val searching: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** Set while the settings dialog probes the server; drives 测试连接 button state. */
    val testing: Boolean = false,
    val testResult: String? = null,
)

class LibraryViewModel(
    private val api: CalibreWebApi,
    private val stateController: ReaderStateController,
) : ViewModel() {

    private val uiState = MutableStateFlow(LibraryUiState(server = stateController.state.value.server))
    val state: StateFlow<LibraryUiState> = uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stateController.state.collect { reader ->
                uiState.update { it.copy(server = reader.server, history = reader.history) }
            }
        }
        if (stateController.state.value.server != null) refresh()
    }

    fun refresh() {
        val server = uiState.value.server ?: return
        uiState.update { it.copy(loading = true, error = null, endReached = false) }
        viewModelScope.launch {
            runCatching { api.newest(server) }
                .onSuccess { feed ->
                    uiState.update {
                        it.copy(
                            loading = false,
                            books = feed.entries,
                            endReached = feed.nextOffset == null,
                            error = if (feed.entries.isEmpty()) "书架是空的" else null,
                        )
                    }
                }
                .onFailure { e -> uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun loadMore() {
        val server = uiState.value.server ?: return
        val current = uiState.value
        if (current.loading || current.loadingMore || current.endReached || current.books.isEmpty()) return
        uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { api.newest(server, current.books.size) }
                .onSuccess { feed ->
                    uiState.update {
                        val merged = (it.books + feed.entries).distinctBy { e -> e.bookId }
                        it.copy(
                            loadingMore = false,
                            books = merged,
                            endReached = feed.nextOffset == null || feed.entries.isEmpty(),
                        )
                    }
                }
                .onFailure {
                    uiState.update { s -> s.copy(loadingMore = false, error = it.message ?: "加载更多失败") }
                }
        }
    }

    fun setSearchQuery(query: String) = uiState.update { it.copy(searchQuery = query) }

    fun search() {
        val server = uiState.value.server ?: return
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            uiState.update { it.copy(searchResults = null) }
            return
        }
        uiState.update { it.copy(searching = true) }
        viewModelScope.launch {
            runCatching { api.search(server, query) }
                .onSuccess { feed -> uiState.update { it.copy(searching = false, searchResults = feed.entries) } }
                .onFailure { e -> uiState.update { it.copy(searching = false, error = e.message ?: "搜索失败") } }
        }
    }

    fun clearSearch() = uiState.update { it.copy(searchQuery = "", searchResults = null) }

    /** Saves the config (persisted by the controller) and reloads the shelf. */
    fun saveServer(server: CalibreServer) {
        stateController.setServer(server)
        uiState.update { it.copy(server = server, books = emptyList(), error = null, testResult = null) }
        refresh()
    }

    fun testConnection(server: CalibreServer) {
        uiState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val ok = runCatching { api.ping(server) }.getOrDefault(false)
            uiState.update {
                it.copy(testing = false, testResult = if (ok) "连接成功" else "连接失败，请检查地址/账号")
            }
        }
    }

    fun clearError() = uiState.update { it.copy(error = null) }

    fun coverUrl(server: CalibreServer, entry: OpdsEntry): String = api.coverUrl(server, entry)

    fun coverAuthHeader(server: CalibreServer): String? = api.coverAuthHeader(server)
}
