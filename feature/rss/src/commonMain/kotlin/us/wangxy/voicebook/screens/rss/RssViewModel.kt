package us.wangxy.voicebook.screens.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.rss.RssListQuery
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssPostsFilter
import us.wangxy.voicebook.rss.RssRepository

data class RssUiState(
    val filter: RssPostsFilter = RssPostsFilter.Unread,
    val feedId: String? = null,
    val syncing: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<RssPostModel>? = null,
)

data class RssFeedsState(
    val feeds: List<FeedWithUnread> = emptyList(),
)

data class FeedWithUnread(val feedId: String, val title: String, val unread: Int)

/** 资讯屏状态：过滤 + 订阅源选择 + 分页文章流 + 同步/搜索。 */
class RssViewModel(
    private val repository: RssRepository,
    library: LocalLibrary,
    private val initializer: LibraryInitializer,
) : ViewModel() {

    private val uiState = MutableStateFlow(RssUiState())
    val state: StateFlow<RssUiState> = uiState.asStateFlow()

    val error: StateFlow<String?> = repository.error
    val syncing: StateFlow<Boolean> = repository.syncing

    private val refreshKey = MutableStateFlow(0)

    /**
     * 首屏数据延迟到资讯页真正停留后再加载。滑入过程中该页就会被组合，若此时立刻
     * 拉数据，加载会造成主线程卡顿、把正在进行的链式翻页手势带偏，表现为「刚切到
     * 资讯、加载条一闪、又弹回前一个 tab」。由界面在 pager 稳定停在本页时调用
     * [activate] 才放行。
     */
    private val activated = MutableStateFlow(false)

    /** 界面确认资讯页已稳定停留后调用（幂等）；之后才允许取数与同步。 */
    fun activate() {
        activated.value = true
    }

    val posts: Flow<PagingData<RssPostModel>> =
        activated.filter { it }.flatMapLatest {
            combine(refreshKey, uiState) { _, state ->
                RssListQuery(filter = state.filter, feedId = state.feedId)
            }.flatMapLatest { query ->
                repository.pager(query)
            }
        }.cachedIn(viewModelScope)

    /** Drawer data: feeds with unread badges, refreshed after each sync. */
    private val feedsState = MutableStateFlow(RssFeedsState())
    val feeds: StateFlow<RssFeedsState> = feedsState.asStateFlow()

    init {
        viewModelScope.launch {
            activated.first { it }
            initializer.awaitReady()
            refreshFeeds()
            repository.sync()
            refreshKey.update { it + 1 }
        }
        viewModelScope.launch {
            repository.syncing.collect { uiState.update { s -> s.copy(syncing = it) } }
        }
    }

    fun refreshFeeds() {
        viewModelScope.launch {
            initializer.awaitReady()
            val feeds = repository.feeds()
            val rows = feeds.map { feed ->
                FeedWithUnread(feed.id, feed.title, repository.unreadCount(feed.id))
            }
            feedsState.value = RssFeedsState(rows)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.sync()
            refreshFeeds()
            refreshKey.update { it + 1 }
        }
    }

    fun setFilter(filter: RssPostsFilter) {
        uiState.update { it.copy(filter = filter) }
        refreshKey.update { it + 1 }
    }

    fun setFeed(feedId: String?) {
        uiState.update { it.copy(feedId = feedId) }
        refreshKey.update { it + 1 }
    }

    fun markAllRead() {
        viewModelScope.launch {
            repository.markAllRead()
            refreshKey.update { it + 1 }
            refreshFeeds()
        }
    }

    fun setSearchQuery(query: String) = uiState.update { it.copy(searchQuery = query) }

    fun search() {
        val needle = uiState.value.searchQuery.trim()
        if (needle.isEmpty()) {
            uiState.update { it.copy(searchResults = null) }
            return
        }
        uiState.update { it.copy(searching = true) }
        viewModelScope.launch {
            val results = repository.search(needle, limit = 100, offset = 0)
            uiState.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun clearSearch() = uiState.update { it.copy(searchQuery = "", searchResults = null) }

    /** Marks one article read and refreshes drawer badges. */
    fun markRead(post: RssPostModel) {
        if (post.read) return
        viewModelScope.launch {
            repository.markRead(listOf(post.id), true)
            refreshFeeds()
        }
    }
}
