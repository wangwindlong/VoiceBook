package us.wangxy.voicebook.screens.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.store.HistoryEntry

/** 书架屏：继续阅读（历史流）+ 全部书籍（与书城同一份缓存优先分页）。 */
class ShelfViewModel(
    private val repository: BookRepository,
    library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val seedExtractor: SeedColorExtractor,
) : ViewModel() {

    private val historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = historyFlow.asStateFlow()

    val error: StateFlow<String?> = repository.error

    private val serverFlow = MutableStateFlow<us.wangxy.voicebook.reader.api.CalibreServer?>(null)
    val server: StateFlow<us.wangxy.voicebook.reader.api.CalibreServer?> = serverFlow.asStateFlow()

    private val refreshKey = MutableStateFlow(0)
    val query = MutableStateFlow("")
    val category = MutableStateFlow("全部")

    val books: Flow<PagingData<CachedBook>> = kotlinx.coroutines.flow.combine(refreshKey, query, category) { _, q, c -> q to c }
        .flatMapLatest { (q, c) -> if (q.isBlank() && c == "全部") repository.shelfPager() else repository.filteredPager(q, c) }
        .cachedIn(viewModelScope)

    private var rawHistory = emptyList<HistoryEntry>()

    init {
        viewModelScope.launch {
            initializer.awaitReady()
            serverFlow.value = repository.server()
            library.history.observeAll().collect { entries ->
                rawHistory = entries
                publishHistory()
            }
        }
        viewModelScope.launch {
            repository.refresh()
            refreshKey.increment()
            seedExtractor.backfill()
        }
        // 「我的」页保存/切换服务器后自动重载认证头与书架（drop(1)：初值由上面 init 负责）。
        viewModelScope.launch {
            repository.serverVersion.drop(1).collect {
                serverFlow.value = repository.server()
                publishHistory()
                repository.refresh()
                refreshKey.increment()
                seedExtractor.backfill()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            refreshKey.increment()
            seedExtractor.backfill()
        }
    }

    fun coverAuthHeader(server: us.wangxy.voicebook.reader.api.CalibreServer): String? =
        repository.coverAuthHeader(server)

    /** History keeps the cover URL of the backend a book was opened from; point it at the current one. */
    private fun publishHistory() {
        val server = serverFlow.value
        historyFlow.value = if (server == null) {
            rawHistory
        } else {
            rawHistory.map { it.copy(coverUrl = repository.historyCoverUrl(server, it.bookId, it.coverUrl)) }
        }
    }

    private fun kotlinx.coroutines.flow.MutableStateFlow<Int>.increment() {
        value += 1
    }
}
