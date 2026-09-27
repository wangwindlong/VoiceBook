package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssPostModel

/** In-memory RSS stores (tests + web fallback); lives in the data package for symmetry. */
class InMemoryRssStores {
    private val postsStore = Posts()
    val feeds: RssFeedStore = Feeds()
    val posts: RssPostStore = postsStore
    val account: RssAccountStore = Account()
    val rssSavedAccounts: us.wangxy.voicebook.data.rss.RssSavedAccountStore = SavedRss()

    private inner class Feeds : RssFeedStore {
        val mutex = Mutex()
        val rows = mutableMapOf<String, RssFeedModel>()

        override suspend fun upsert(feed: RssFeedModel) = mutex.withLock {
            rows[feed.id] = feed
            Unit
        }

        override suspend fun all(): List<RssFeedModel> = mutex.withLock { rows.values.sortedBy { it.title } }

        override suspend fun get(id: String): RssFeedModel? = mutex.withLock { rows[id] }

        override suspend fun delete(id: String) = mutex.withLock {
            rows.remove(id)
            // Cascade: a deleted feed takes its cached posts with it.
            postsStore.deleteForFeed(id)
            Unit
        }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) = mutex.withLock {
            rows[id]?.let { rows[id] = it.copy(seedColor = seedColor) }
            Unit
        }

        override suspend fun clearAll() = mutex.withLock {
            rows.clear()
            Unit
        }
    }

    private inner class Posts : RssPostStore {
        val mutex = Mutex()
        val rows = MutableStateFlow<Map<String, RssPostModel>>(emptyMap())

        private fun filterAll(query: RssPostsQuery): List<RssPostModel> =
            rows.value.values.asSequence()
                .filter { query.feedId == null || it.feedId == query.feedId }
                .filter { !query.unreadOnly || !it.read }
                .filter { !query.starredOnly || it.starred }
                .sortedByDescending { it.publishedAt }
                .toList()

        override fun observeLatest(): Flow<List<RssPostModel>> =
            rows.asStateFlow().map { entries -> entries.values.sortedByDescending { it.publishedAt } }

        override suspend fun upsertAll(posts: List<RssPostModel>) = mutex.withLock {
            val next = rows.value.toMutableMap()
            posts.forEach { post ->
                val existing = next[post.id]
                next[post.id] = existing?.copy(
                    title = post.title,
                    publishedAt = post.publishedAt,
                    summary = post.summary,
                    contentHtml = post.contentHtml,
                    imageUrl = post.imageUrl ?: existing.imageUrl,
                    audioUrl = post.audioUrl ?: existing.audioUrl,
                ) ?: post
            }
            rows.value = next
            Unit
        }

        override suspend fun page(limit: Int, offset: Int, query: RssPostsQuery): List<RssPostModel> =
            mutex.withLock { filterAll(query).drop(offset).take(limit) }

        override suspend fun count(query: RssPostsQuery): Int = mutex.withLock { filterAll(query).size }

        override suspend fun get(id: String): RssPostModel? = mutex.withLock { rows.value[id] }

        override suspend fun setRead(ids: List<String>, read: Boolean) = mutex.withLock {
            rows.value = rows.value.toMutableMap().apply {
                ids.forEach { id -> this[id]?.let { this[id] = it.copy(read = read) } }
            }
            Unit
        }

        override suspend fun markAllRead() = setRead(rows.value.keys.toList(), true)

        override suspend fun setStarred(id: String, starred: Boolean) = mutex.withLock {
            rows.value[id]?.let { rows.value = rows.value + (id to it.copy(starred = starred)) }
            Unit
        }

        override suspend fun setAudioProgress(id: String, positionMs: Long, durationMs: Long) = mutex.withLock {
            rows.value[id]?.let { rows.value = rows.value + (id to it.copy(audioPositionMs = positionMs, audioDurationMs = durationMs)) }
            Unit
        }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) = mutex.withLock {
            rows.value[id]?.let { rows.value = rows.value + (id to it.copy(seedColor = seedColor)) }
            Unit
        }

        override suspend fun postsNeedingSeedColor(limit: Int): List<RssPostModel> =
            mutex.withLock { filterAll(RssPostsQuery()).filter { it.imageUrl != null && it.seedColor == null }.take(limit) }

        override suspend fun search(text: String, limit: Int, offset: Int): List<RssPostModel> = mutex.withLock {
            val needle = text.trim()
            filterAll(RssPostsQuery())
                .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.summary.contains(needle, ignoreCase = true) }
                .drop(offset).take(limit)
        }

        override suspend fun deleteReadBefore(thresholdMs: Long) = mutex.withLock {
            rows.value = rows.value.filterValues { !it.read || it.starred || it.publishedAt >= thresholdMs }
            Unit
        }

        override suspend fun deleteForFeed(feedId: String) = mutex.withLock {
            rows.value = rows.value.filterValues { it.feedId != feedId }
            Unit
        }

        override suspend fun clear() = mutex.withLock {
            rows.value = emptyMap()
            Unit
        }
    }

    private inner class SavedRss : RssSavedAccountStore {
        val mutex = Mutex()
        val rows = mutableMapOf<String, us.wangxy.voicebook.rss.RssSavedAccount>()

        override suspend fun saved(): List<us.wangxy.voicebook.rss.RssSavedAccount> =
            mutex.withLock { rows.values.sortedByDescending { it.lastUsedAt } }

        override suspend fun upsert(account: us.wangxy.voicebook.rss.RssSavedAccount) = mutex.withLock {
            rows[account.id] = account
            Unit
        }

        override suspend fun delete(id: String) = mutex.withLock {
            rows.remove(id)
            Unit
        }
    }

    private inner class Account : RssAccountStore {
        val mutex = Mutex()
        var current: RssAccountModel? = null

        override suspend fun get(): RssAccountModel? = mutex.withLock { current }

        override suspend fun set(account: RssAccountModel?) = mutex.withLock {
            current = account
            Unit
        }
    }
}
