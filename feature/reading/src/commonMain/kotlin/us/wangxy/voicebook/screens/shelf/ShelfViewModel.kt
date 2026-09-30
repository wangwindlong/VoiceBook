package us.wangxy.voicebook.screens.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    private val sessions: us.wangxy.voicebook.data.ReaderSessionRepository,
    private val content: us.wangxy.voicebook.bff.ContentApi,
    private val personalization: us.wangxy.voicebook.data.PersonalizationRepository,
) : ViewModel() {

    private val historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = historyFlow.asStateFlow()

    val error: StateFlow<String?> = repository.error

    private val serverFlow = MutableStateFlow<us.wangxy.voicebook.reader.api.CalibreServer?>(null)
    val server: StateFlow<us.wangxy.voicebook.reader.api.CalibreServer?> = serverFlow.asStateFlow()

    private val refreshKey = MutableStateFlow(0)
    val query = MutableStateFlow("")
    val category = MutableStateFlow("全部")
    val tags = MutableStateFlow<List<String>>(emptyList())
    val sort = MutableStateFlow("added")
    val tagMode = MutableStateFlow("any")
    val availableTags = MutableStateFlow<List<us.wangxy.voicebook.bff.contract.CalibreTag>>(emptyList())
    val immediate = MutableStateFlow<List<CachedBook>?>(null)
    val filtering = MutableStateFlow(false)
    private var filterJob: kotlinx.coroutines.Job? = null
    fun setQuery(value: String) {
        query.value=value
        filterJob?.cancel()
        filterJob=viewModelScope.launch {
            immediate.value=repository.cachedFilter(us.wangxy.voicebook.data.LibraryFilter(value,category.value,tags.value,tagMode.value,sort.value))
            filtering.value=true
            kotlinx.coroutines.delay(300)
            personalization.search(value)
        }
    }
    fun filtersApplied() { immediate.value=null; filtering.value=false }
    private suspend fun loadTags() {
        availableTags.value=emptyList()
        try { availableTags.value=content.tags().tags }
        catch(e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
    val books: Flow<PagingData<CachedBook>> = kotlinx.coroutines.flow.combine(refreshKey, query.debounce(300), category, tags, sort) { _, q, c, t, s ->
        us.wangxy.voicebook.data.LibraryFilter(q,c,t,tagMode.value,s)
    }.combine(tagMode) { filter, mode -> filter.copy(tagMode=mode) }
        .flatMapLatest { f -> repository.filteredPager(f.query,f.category,f.tags,f.tagMode,f.sort) }
        .cachedIn(viewModelScope)

    private var rawHistory = emptyList<HistoryEntry>()

    init {
        viewModelScope.launch {
            initializer.awaitReady()
            serverFlow.value = repository.server()
            sessions.observeHistory().collect { entries ->
                rawHistory = entries
                publishHistory()
            }
        }
        viewModelScope.launch {
            loadTags()
            sessions.refreshHistory()
            repository.refresh()
            refreshKey.increment()
            seedExtractor.backfill()
        }
        // 「我的」页保存/切换服务器后自动重载认证头与书架（drop(1)：初值由上面 init 负责）。
        viewModelScope.launch {
            repository.serverVersion.drop(1).collect {
                serverFlow.value = repository.server()
                publishHistory()
                loadTags()
            sessions.refreshHistory()
            repository.refresh()
                refreshKey.increment()
                seedExtractor.backfill()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadTags()
            sessions.refreshHistory()
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
