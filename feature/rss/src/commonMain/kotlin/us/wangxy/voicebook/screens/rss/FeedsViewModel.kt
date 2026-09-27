package us.wangxy.voicebook.screens.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssSavedAccount
import us.wangxy.voicebook.rss.RssRepository
import us.wangxy.voicebook.rss.RssSyncMode

data class FeedsUiState(
    val feeds: List<RssFeedModel> = emptyList(),
    val account: RssAccountModel? = null,
    val savedAccounts: List<RssSavedAccount> = emptyList(),
    /** True while a feed URL is being fetched/validated. */
    val adding: Boolean = false,
    val message: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
)

/** 订阅管理：源列表增删 + 同步账户（本地 / Miniflux）。 */
class FeedsViewModel(
    private val repository: RssRepository,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
) : ViewModel() {

    private val uiState = MutableStateFlow(FeedsUiState())
    val state: StateFlow<FeedsUiState> = uiState.asStateFlow()

    init {
        viewModelScope.launch {
            initializer.awaitReady()
            reload()
        }
    }

    fun reload() {
        viewModelScope.launch {
            uiState.update {
                it.copy(
                    feeds = repository.feeds(),
                    account = repository.account(),
                    savedAccounts = repository.savedRssAccounts(),
                )
            }
        }
    }

    /** 切换到已保存账号：游标清零、后端变化时清缓存，然后立即同步。 */
    fun activateRssAccount(id: String) {
        viewModelScope.launch {
            if (repository.activateRssAccount(id)) {
                repository.sync()
                reload()
            }
        }
    }

    fun deleteRssAccount(id: String) {
        viewModelScope.launch {
            repository.deleteRssAccount(id)
            reload()
        }
    }

    fun addFeed(url: String) {
        if (uiState.value.adding) return
        uiState.update { it.copy(adding = true, message = null) }
        viewModelScope.launch {
            val feed = repository.addFeed(url)
            uiState.update {
                it.copy(
                    adding = false,
                    message = if (feed == null) "添加失败，请检查地址" else "已添加「${feed.title}」",
                )
            }
            reload()
        }
    }

    fun removeFeed(feedId: String) {
        viewModelScope.launch {
            repository.removeFeed(feedId)
            reload()
        }
    }

    fun saveAccount(account: RssAccountModel) {
        viewModelScope.launch {
            repository.setAccount(account)
            uiState.update { it.copy(account = account, testResult = null) }
            repository.sync()
            reload()
        }
    }

    fun testMiniflux(serverUrl: String, token: String) {
        uiState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val ok = runCatching { repository.testMiniflux(serverUrl, token) }.getOrDefault(false)
            uiState.update {
                it.copy(testing = false, testResult = if (ok) "连接成功" else "连接失败，请检查地址与 Token")
            }
        }
    }
}
