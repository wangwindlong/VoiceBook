@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.rss

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.data.widget.WidgetSnapshot
import us.wangxy.voicebook.data.widget.writeWidgetSnapshot
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.isSignedIn
import kotlin.time.Clock

/** Filter + optional feed scoping for the article list. */
data class RssListQuery(
    val filter: RssPostsFilter = RssPostsFilter.All,
    val feedId: String? = null,
    val searchText: String? = null,
)

/** Article paging source over the cache (same cache-first stance as the book shelf). */
class RssPostsPagingSource(
    private val repository: RssRepository,
    private val query: RssListQuery,
    private val pageSize: Int,
) : PagingSource<Int, RssPostModel>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RssPostModel> {
        val pageIndex = params.key ?: 0
        return try {
            val posts = repository.postsPage(pageSize, pageIndex, query)
            LoadResult.Page(
                data = posts,
                prevKey = if (pageIndex == 0) null else pageIndex - 1,
                nextKey = if (posts.size < pageSize) null else pageIndex + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, RssPostModel>): Int? =
        state.anchorPosition?.let { anchor -> anchor / pageSize }
}

/**
 * RSS facade: sync dispatch (local / Miniflux), paging, read-star progress, FTS
 * search and the widget snapshot. All reads go through the LocalLibrary stores so
 * cold starts render from disk exactly like the book shelf does.
 */
class RssRepository(
    private val client: HttpClient,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val extractor: SeedColorExtractor,
    private val localSync: LocalSyncCoordinator,
    private val minifluxSync: MinifluxSyncCoordinator,
    private val minifluxApi: MinifluxApi,
    private val session: BffSession,
) {

    private val syncingFlow = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncingFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    /** Bumped after sync so pagers restart from page 0 with fresh filters. */
    private val refreshKey = MutableStateFlow(0)
    fun refreshTrigger(): StateFlow<Int> = refreshKey

    fun pager(query: RssListQuery): Flow<PagingData<RssPostModel>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = 6, enablePlaceholders = false),
        pagingSourceFactory = { RssPostsPagingSource(this, query, PAGE_SIZE) },
    ).flow

    suspend fun account(): RssAccountModel? {
        initializer.awaitReady()
        return library.rssAccount.get()
    }

    suspend fun setAccount(account: RssAccountModel?, label: String = "") {
        initializer.awaitReady()
        applyAccount(account ?: RssAccountModel(mode = RssSyncMode.Local), label)
    }

    suspend fun savedRssAccounts(): List<RssSavedAccount> {
        initializer.awaitReady()
        return library.rssSavedAccounts.saved()
    }

    /** 切换到已保存账号：游标清零全量拉取；后端变化时清空订阅与文章缓存。 */
    suspend fun activateRssAccount(id: String): Boolean {
        initializer.awaitReady()
        val saved = library.rssSavedAccounts.saved().firstOrNull { it.id == id } ?: return false
        applyAccount(
            RssAccountModel(
                mode = saved.mode,
                serverUrl = saved.serverUrl,
                token = saved.token,
                lastEntryId = null,
                lastSyncedAt = 0L,
            ),
            saved.label,
        )
        return true
    }

    suspend fun deleteRssAccount(id: String) {
        initializer.awaitReady()
        library.rssSavedAccounts.delete(id)
    }

    /** 生效账号落库 + 自动归档到已保存列表；后端变化时清空对应缓存。 */
    private suspend fun applyAccount(account: RssAccountModel, label: String = "") {
        val previous = library.rssAccount.get()
        val backendChanged = previous?.mode != account.mode ||
            (account.mode == RssSyncMode.Miniflux && previous?.serverUrl != account.serverUrl)
        library.rssAccount.set(account)
        val now = Clock.System.now().toEpochMilliseconds()
        if (account.mode == RssSyncMode.Miniflux) {
            // 统一账号（经 BFF，token 为空）不归档：没有可复用的凭据，退出登录后也用不了。
            if (account.token != null) library.rssSavedAccounts.upsert(
                RssSavedAccount(
                    id = rssAccountId(account.serverUrl.orEmpty()),
                    label = label.ifBlank { account.serverUrl.orEmpty() },
                    mode = account.mode,
                    serverUrl = account.serverUrl,
                    token = account.token,
                    lastUsedAt = now,
                ),
            )
        } else {
            library.rssSavedAccounts.upsert(RssSavedAccount(id = LocalAccountId, label = "本地拉取", mode = RssSyncMode.Local, lastUsedAt = now))
        }
        if (backendChanged && (previous?.mode == RssSyncMode.Miniflux || account.mode == RssSyncMode.Miniflux)) {
            // Miniflux 的条目 id 是纯数字，跨服务器会撞；切后端必须清缓存。
            library.rssFeeds.clearAll()
            library.rssPosts.clear()
        }
    }

    private fun rssAccountId(serverUrl: String): String = "miniflux|$serverUrl"

    /** Probes Miniflux: [direct] credentials from the account dialog, else the active route. */
    suspend fun testMiniflux(direct: MinifluxCredentials? = null): Boolean = minifluxApi.me(direct)

    /**
     * Login / logout changes who answers Miniflux calls (the BFF's user vs. the user's own server),
     * so entry ids and the sync cursor from before are meaningless. Local mode is untouched; the
     * BFF-only account (no token of its own) falls back to local mode after logout.
     */
    suspend fun onSessionChanged(signedIn: Boolean) {
        initializer.awaitReady()
        val account = library.rssAccount.get() ?: return
        if (account.mode != RssSyncMode.Miniflux) return
        if (!signedIn && account.token == null) {
            applyAccount(RssAccountModel(mode = RssSyncMode.Local))
        } else {
            library.rssAccount.set(account.copy(lastEntryId = null, lastSyncedAt = 0L))
            library.rssFeeds.clearAll()
            library.rssPosts.clear()
        }
        refreshKey.update { it + 1 }
        if (signedIn || account.token != null) sync()
    }

    /**
     * Turns on 资讯 syncing through the BFF's Miniflux channel.
     *
     * The server URL is the BFF itself and there is no token to store: [MinifluxApi] resolves both
     * from the signed-in session on every request. The BFF has already created the matching
     * Miniflux user during registration, together with the default feeds.
     */
    suspend fun enableUnifiedNews() {
        initializer.awaitReady()
        applyAccount(
            RssAccountModel(
                mode = RssSyncMode.Miniflux,
                serverUrl = session.baseUrl(),
                token = null,
                lastEntryId = null,
                lastSyncedAt = 0L,
            ),
        )
    }

    /** Turns 资讯 back to on-device RSS fetching (the default). */
    suspend fun disableUnifiedNews() {
        initializer.awaitReady()
        applyAccount(RssAccountModel(mode = RssSyncMode.Local))
    }

    suspend fun postsPage(pageSize: Int, pageIndex: Int, query: RssListQuery): List<RssPostModel> {
        initializer.awaitReady()
        val offset = pageIndex * pageSize
        if (library.rssAccount.get()?.mode == RssSyncMode.Miniflux) {
            return minifluxSync.loadPage(pageSize, offset, query)
        }
        query.searchText?.takeIf { it.isNotBlank() }?.let {
            return library.rssPosts.search(it, pageSize, offset)
        }
        return library.rssPosts.page(
            pageSize, offset,
            RssPostsQuery(
                feedId = query.feedId,
                unreadOnly = query.filter == RssPostsFilter.Unread,
                starredOnly = query.filter == RssPostsFilter.Starred,
            ),
        )
    }

    suspend fun postById(id: String): RssPostModel? {
        initializer.awaitReady()
        return library.rssPosts.get(id)
    }

    suspend fun feeds(): List<RssFeedModel> {
        initializer.awaitReady()
        return library.rssFeeds.all()
    }

    suspend fun unreadCount(feedId: String? = null): Int {
        initializer.awaitReady()
        return library.rssPosts.count(RssPostsQuery(feedId = feedId, unreadOnly = true))
    }

    suspend fun markRead(ids: List<String>, read: Boolean) {
        initializer.awaitReady()
        library.rssPosts.setRead(ids, read)
        pushReadState(ids, read)
    }

    suspend fun markAllRead() {
        initializer.awaitReady()
        if (library.rssAccount.get()?.mode == RssSyncMode.Miniflux) {
            minifluxSync.markAllRead()
        }
        library.rssPosts.markAllRead()
    }

    suspend fun setStarred(id: String, starred: Boolean) {
        initializer.awaitReady()
        library.rssPosts.setStarred(id, starred)
        pushStarred(id, starred)
    }

    suspend fun setAudioProgress(id: String, positionMs: Long, durationMs: Long) {
        initializer.awaitReady()
        library.rssPosts.setAudioProgress(id, positionMs, durationMs)
    }

    suspend fun search(text: String, limit: Int, offset: Int): List<RssPostModel> {
        initializer.awaitReady()
        return library.rssPosts.search(text, limit, offset)
    }

    suspend fun removeFeed(feedId: String) {
        initializer.awaitReady()
        val account = library.rssAccount.get()
        if (account?.mode == RssSyncMode.Miniflux && feedId.startsWith("mf:")) {
            feedId.removePrefix("mf:").toLongOrNull()?.let { minifluxApi.deleteFeed(it) }
        }
        library.rssFeeds.delete(feedId)
    }

    /** Adds a feed and immediately pulls its content. Returns null on failure. */
    suspend fun addFeed(url: String): RssFeedModel? {
        if (!session.isSignedIn) { errorFlow.value="请先登录统一账号"; return null }
        enableUnifiedNews()
        initializer.awaitReady()
        val normalized = url.trim()
        if (normalized.isEmpty()) return null
        val account = library.rssAccount.get()
        val feedUrl = if (normalized.startsWith("http")) normalized else "https://$normalized"

        if (account?.mode == RssSyncMode.Miniflux) {
            val created = minifluxApi.createFeed(feedUrl)
            if (created == null) {
                errorFlow.value = if (session.isSignedIn) "订阅失败，请检查订阅地址" else "订阅失败，请检查地址与 Token"
                return null
            }
            sync()
            return library.rssFeeds.all().firstOrNull { it.id == "mf:$created" }
        }

        return try {
            val parsed = RssParser.parse(RssFeedFetcher(client).fetchFeedText(feedUrl))
            val feed = RssFeedModel(
                id = "local:$feedUrl",
                title = parsed.title.ifBlank { feedUrl },
                link = parsed.link,
                siteUrl = parsed.siteUrl,
                feedUrl = feedUrl,
                imageUrl = parsed.imageUrl,
                lastSyncedAt = Clock.System.now().toEpochMilliseconds(),
            )
            library.rssFeeds.upsert(feed)
            library.rssPosts.upsertAll(
                parsed.items.map { item ->
                    RssPostModel(
                        id = LocalSyncCoordinator.postKey(feed.id, item.guid),
                        feedId = feed.id,
                        feedTitle = feed.title,
                        title = item.title,
                        link = item.link,
                        publishedAt = item.publishedAt,
                        summary = item.summary,
                        contentHtml = item.contentHtml,
                        imageUrl = item.imageUrl,
                        audioUrl = item.audioUrl,
                    )
                },
            )
            errorFlow.value = null
            feed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorFlow.value = e.message ?: "订阅失败"
            null
        }
    }

    /** Dispatches to the active coordinator, then themes, prunes and snapshots. */
    suspend fun sync() {
        if (!session.isSignedIn) return
        if (syncingFlow.value) return
        syncingFlow.value = true
        try {
            initializer.awaitReady()
            // A newly signed-in account has no RSS preference row yet. The BFF
            // provisioned its default feeds at registration; select that shared
            // account on first use so those feeds appear without manual setup.
            if (library.rssAccount.get() == null && session.isSignedIn) {
                applyAccount(
                    RssAccountModel(
                        mode = RssSyncMode.Miniflux,
                        serverUrl = session.baseUrl(),
                        token = null,
                    ),
                )
            }
            val coordinator = when (library.rssAccount.get()?.mode) {
                RssSyncMode.Miniflux -> minifluxSync
                else -> localSync
            }
            val hasNew = coordinator.sync()
            if (hasNew) refreshKey.update { it + 1 }
            errorFlow.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorFlow.value = e.message ?: "同步失败"
        } finally {
            syncingFlow.value = false
        }
        backfillSeedColors()
        pruneOldPosts()
        writeSnapshot()
    }

    /** Extracts accent colors for image posts missing one (drives 封面取色). */
    suspend fun backfillSeedColors(limit: Int = 12) {
        val posts = library.rssPosts.postsNeedingSeedColor(limit)
        for (post in posts) {
            val url = post.imageUrl ?: continue
            val color = extractor.seedColorForUrl(url) ?: continue
            library.rssPosts.updateSeedColor(post.id, color)
        }
    }

    private suspend fun pruneOldPosts() {
        val now = Clock.System.now().toEpochMilliseconds()
        library.rssPosts.deleteReadBefore(now - RetainMillis)
    }

    private suspend fun writeSnapshot() {
        val unread = library.rssPosts.count(RssPostsQuery(unreadOnly = true))
        val latest = library.rssPosts.page(3, 0, RssPostsQuery())
        writeWidgetSnapshot(
            WidgetSnapshot(
                unread = unread,
                items = latest.map { WidgetSnapshot.Item(id = it.id, title = it.title, feed = it.feedTitle) },
            ),
        )
    }

    /** Miniflux accounts get their read state pushed; local mode is local-only. */
    private suspend fun pushReadState(ids: List<String>, read: Boolean) {
        val account = library.rssAccount.get() ?: return
        if (account.mode != RssSyncMode.Miniflux) return
        ids.chunked(MinifluxBatchSize).forEach { batch ->
            minifluxApi.markEntries(batch, if (read) "read" else "unread")
        }
    }

    private suspend fun pushStarred(id: String, starred: Boolean) {
        val account = library.rssAccount.get() ?: return
        if (account.mode != RssSyncMode.Miniflux) return
        if (starred) minifluxApi.toggleStarred(id)
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MinifluxBatchSize = 100
        const val RetainMillis = 90L * 24 * 3_600_000
        const val LocalAccountId = "local"
    }
}

