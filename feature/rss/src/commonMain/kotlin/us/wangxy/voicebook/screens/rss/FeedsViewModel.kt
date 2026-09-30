package us.wangxy.voicebook.screens.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.isSignedIn
import us.wangxy.voicebook.rss.MinifluxCredentials
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssSavedAccount
import us.wangxy.voicebook.rss.RssRepository
import us.wangxy.voicebook.rss.RssSyncMode

data class FeedsUiState(
    val categories: Map<String, String> = emptyMap(),
    val feeds: List<RssFeedModel> = emptyList(),
    val account: RssAccountModel? = null,
    val savedAccounts: List<RssSavedAccount> = emptyList(),
    /** True while a feed URL is being fetched/validated. */
    val adding: Boolean = false,
    val message: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    /** Whether somebody is signed in — 资讯 (server sync) needs a BFF session. */
    val signedIn: Boolean = false,
)

/** 订阅管理：源列表增删 + 资讯同步（本地抓取 / 已登录走统一账号 / 未登录直连自己的 Miniflux）。 */
class FeedsViewModel(
    private val repository: RssRepository,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val session: BffSession,
    private val content: us.wangxy.voicebook.bff.ContentApi,
) : ViewModel() {

    private val uiState = MutableStateFlow(FeedsUiState())
    val state: StateFlow<FeedsUiState> = uiState.asStateFlow()

    init {
        viewModelScope.launch {
            initializer.awaitReady()
            // Login / logout switches the Miniflux route (and may reset the account), so re-read everything.
            session.signedInUser.collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val signedIn = session.isSignedIn
            val feeds = repository.feeds()
            val categories = feeds.associate { it.id to (library.settings.get("feed.category.${it.id}") ?: "未分类") }.toMutableMap()
            if (signedIn) {
                try {
                    val remote = content.categories()
                    feeds.forEach { feed -> remote[us.wangxy.voicebook.bff.contentKey(feed.id)]?.let { categories[feed.id] = it } }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { uiState.update { it.copy(message = e.message) } }
            }
            uiState.update {
                it.copy(
                    feeds = feeds,
                    categories = categories,
                    account = repository.account(),
                    savedAccounts = repository.savedRssAccounts(),
                    signedIn = signedIn,
                )
            }
        }
    }

    fun setCategory(feedId: String, category: String) {
        viewModelScope.launch {
            try {
                if (session.isSignedIn) content.setCategory(us.wangxy.voicebook.bff.contentKey(feedId), category)
                library.settings.put("feed.category.$feedId", category)
                uiState.update { it.copy(categories = it.categories + (feedId to category), message = null) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { uiState.update { it.copy(message = e.message) } }
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

    /** 一句话开关：开启服务端统一账号同步资讯。 */
    fun enableUnifiedNews() {
        viewModelScope.launch {
            repository.enableUnifiedNews()
            uiState.update { it.copy(account = repository.account(), testResult = null) }
            repository.sync()
            reload()
        }
    }

    /** 关闭统一账号，回到手机本地抓取。 */
    fun disableUnifiedNews() {
        viewModelScope.launch {
            repository.disableUnifiedNews()
            uiState.update { it.copy(account = repository.account(), testResult = null) }
            reload()
        }
    }

    fun toggleUnifiedNews(enabled: Boolean) {
        if (enabled) enableUnifiedNews() else disableUnifiedNews()
    }

    /** 未登录时的 Miniflux 账号弹窗：直连测试填写的地址与 Token。 */
    fun testMinifluxAccount(serverUrl: String, token: String) {
        uiState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val ok = runCatching { repository.testMiniflux(MinifluxCredentials(serverUrl, token)) }.getOrDefault(false)
            uiState.update {
                it.copy(testing = false, testResult = if (ok) "连接成功" else "连接失败，请检查地址与 Token")
            }
        }
    }

    /** 用当前登录态探测服务端资讯通道（无需填地址/Token）。 */
    fun testMiniflux() {
        uiState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val ok = runCatching { repository.testMiniflux() }.getOrDefault(false)
            uiState.update {
                it.copy(testing = false, testResult = if (ok) "连接成功" else "连接失败，请确认已登录")
            }
        }
    }
}
