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
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssSavedAccount
import us.wangxy.voicebook.rss.RssSyncMode
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.data.widget.WidgetSnapshot
import us.wangxy.voicebook.data.widget.writeWidgetSnapshot
import us.wangxy.voicebook.rss.RssPostsFilter
import kotlin.time.Clock

/** Filter + optional feed scoping for the article list. */
data class RssListQuery(
    val filter: RssPostsFilter = RssPostsFilter.All,
    val feedId: String? = null,
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
    private suspend fun applyAccount(account: RssAccountModel, label: String) {
        val previous = library.rssAccount.get()
        val backendChanged = previous?.mode != account.mode ||
            (account.mode == RssSyncMode.Miniflux && previous?.serverUrl != account.serverUrl)
        library.rssAccount.set(account)
        val now = Clock.System.now().toEpochMilliseconds()
        if (account.mode == RssSyncMode.Miniflux) {
            library.rssSavedAccounts.upsert(
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

    suspend fun testMiniflux(serverUrl: String, token: String): Boolean =
        minifluxApi.me(serverUrl, token)

    suspend fun postsPage(pageSize: Int, pageIndex: Int, query: RssListQuery): List<RssPostModel> {
        initializer.awaitReady()
        return library.rssPosts.page(
            pageSize, pageIndex * pageSize,
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
        library.rssPosts.markAllRead()
        pushReadState(emptyList(), true)
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
            feedId.removePrefix("mf:").toLongOrNull()?.let { minifluxApi.deleteFeed(account.serverUrl.orEmpty(), account.token.orEmpty(), it) }
        }
        library.rssFeeds.delete(feedId)
    }

    /** Adds a feed and immediately pulls its content. Returns null on failure. */
    suspend fun addFeed(url: String): RssFeedModel? {
        initializer.awaitReady()
        val normalized = url.trim()
        if (normalized.isEmpty()) return null
        val account = library.rssAccount.get()
        val feedUrl = if (normalized.startsWith("http")) normalized else "https://$normalized"

        if (account?.mode == RssSyncMode.Miniflux) {
            val created = minifluxApi.createFeed(account.serverUrl.orEmpty(), account.token.orEmpty(), feedUrl)
            if (created == null) {
                errorFlow.value = "订阅失败，请检查地址与 Token"
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
        if (syncingFlow.value) return
        syncingFlow.value = true
        try {
            initializer.awaitReady()
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
        val targets = ids.ifEmpty { library.rssPosts.page(1000, 0, RssPostsQuery()).filter { it.read }.map { it.id } }
        targets.chunked(100).forEach { batch ->
            minifluxApi.markEntries(account.serverUrl.orEmpty(), account.token.orEmpty(), batch, if (read) "read" else "unread")
        }
    }

    private suspend fun pushStarred(id: String, starred: Boolean) {
        val account = library.rssAccount.get() ?: return
        if (account.mode != RssSyncMode.Miniflux) return
        if (starred) minifluxApi.toggleStarred(account.serverUrl.orEmpty(), account.token.orEmpty(), id)
    }

    companion object {
        const val PAGE_SIZE = 20
        const val RetainMillis = 90L * 24 * 3_600_000
        const val LocalAccountId = "local"
    }
}

