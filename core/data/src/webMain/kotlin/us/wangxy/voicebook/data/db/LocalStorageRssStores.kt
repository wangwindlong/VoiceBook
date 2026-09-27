package us.wangxy.voicebook.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssSavedAccount
import us.wangxy.voicebook.rss.RssSyncMode

/**
 * localStorage-backed RSS stores. Three JSON documents (feeds / posts / account);
 * the posts document is capped to keep well inside the ~5MB storage quota, and
 * full-text search degrades to a case-insensitive substring scan.
 */
internal class LocalStorageRssStores(private val raw: (key: String) -> String?, private val write: (key: String, value: String) -> Unit) {

    val rssSavedAccounts: RssSavedAccountStore = SavedAccounts()


    private val json = Json { ignoreUnknownKeys = true }

    val feeds: RssFeedStore = Feeds()
    val posts: RssPostStore = Posts()
    val account: RssAccountStore = Account()

    @Serializable
    private data class AccountDoc(
        val mode: String = "Local",
        val serverUrl: String? = null,
        val token: String? = null,
        val lastEntryId: String? = null,
        val lastSyncedAt: Long = 0L,
    )

    private fun readPosts(): MutableList<RssPostModel> {
        val rawValue = raw(KEY_POSTS) ?: return mutableListOf()
        return runCatching {
            json.decodeFromString(ListSerializer(RssPostModel.serializer()), rawValue).toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writePosts(posts: List<RssPostModel>) {
        write(KEY_POSTS, json.encodeToString(ListSerializer(RssPostModel.serializer()), posts))
    }

    private fun readFeeds(): MutableList<RssFeedModel> {
        val rawValue = raw(KEY_FEEDS) ?: return mutableListOf()
        return runCatching {
            json.decodeFromString(ListSerializer(RssFeedModel.serializer()), rawValue).toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private inner class Feeds : RssFeedStore {

        override suspend fun upsert(feed: RssFeedModel) {
            val rows = readFeeds()
            rows.removeAll { it.id == feed.id }
            rows.add(feed)
            write(KEY_FEEDS, json.encodeToString(ListSerializer(RssFeedModel.serializer()), rows.sortedBy { it.title }))
        }

        override suspend fun all(): List<RssFeedModel> = readFeeds()

        override suspend fun get(id: String): RssFeedModel? = readFeeds().firstOrNull { it.id == id }

        override suspend fun delete(id: String) {
            val rows = readFeeds()
            rows.removeAll { it.id == id }
            write(KEY_FEEDS, json.encodeToString(ListSerializer(RssFeedModel.serializer()), rows))
            val posts = readPosts()
            posts.removeAll { it.feedId == id }
            writePosts(posts)
        }

        override suspend fun clearAll() {
            write(KEY_FEEDS, "[]")
            writePosts(emptyList())
        }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) {
            val rows = readFeeds().map { if (it.id == id) it.copy(seedColor = seedColor) else it }
            write(KEY_FEEDS, json.encodeToString(ListSerializer(RssFeedModel.serializer()), rows))
        }
    }

    private inner class Posts : RssPostStore {

        // No change notifications in localStorage; refresh the flow after every write.
        private val latest = MutableStateFlow(readPosts().sortedByDescending { it.publishedAt })

        override fun observeLatest(): Flow<List<RssPostModel>> = latest.asStateFlow()

        private fun refreshLatest() {
            latest.value = readPosts().sortedByDescending { it.publishedAt }
        }

        override suspend fun upsertAll(posts: List<RssPostModel>) {
            val rows = readPosts()
            posts.forEach { post ->
                val index = rows.indexOfFirst { it.id == post.id }
                if (index >= 0) {
                    val existing = rows[index]
                    rows[index] = existing.copy(
                        title = post.title,
                        publishedAt = post.publishedAt,
                        summary = post.summary,
                        contentHtml = post.contentHtml,
                        imageUrl = post.imageUrl ?: existing.imageUrl,
                        audioUrl = post.audioUrl ?: existing.audioUrl,
                    )
                } else {
                    rows.add(post)
                }
            }
            writePosts(rows.sortedByDescending { it.publishedAt }.take(MaxPosts))
            refreshLatest()
        }

        override suspend fun page(limit: Int, offset: Int, query: RssPostsQuery): List<RssPostModel> =
            filterAll(query).drop(offset).take(limit)

        override suspend fun count(query: RssPostsQuery): Int = filterAll(query).size

        override suspend fun get(id: String): RssPostModel? = readPosts().firstOrNull { it.id == id }

        override suspend fun setRead(ids: List<String>, read: Boolean) {
            val idSet = ids.toSet()
            writePosts(readPosts().map { if (it.id in idSet) it.copy(read = read) else it })
            refreshLatest()
        }

        override suspend fun markAllRead() {
            writePosts(readPosts().map { it.copy(read = true) })
            refreshLatest()
        }

        override suspend fun setStarred(id: String, starred: Boolean) {
            writePosts(readPosts().map { if (it.id == id) it.copy(starred = starred) else it })
            refreshLatest()
        }

        override suspend fun setAudioProgress(id: String, positionMs: Long, durationMs: Long) {
            writePosts(readPosts().map {
                if (it.id == id) it.copy(audioPositionMs = positionMs, audioDurationMs = durationMs) else it
            })
        }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) {
            writePosts(readPosts().map { if (it.id == id) it.copy(seedColor = seedColor) else it })
        }

        override suspend fun postsNeedingSeedColor(limit: Int): List<RssPostModel> =
            readPosts().filter { it.imageUrl != null && it.seedColor == null }.take(limit)

        override suspend fun search(text: String, limit: Int, offset: Int): List<RssPostModel> {
            val needle = text.trim()
            return readPosts()
                .filter { it.title.contains(needle, ignoreCase = true) || it.summary.contains(needle, ignoreCase = true) }
                .drop(offset).take(limit)
        }

        override suspend fun deleteReadBefore(thresholdMs: Long) {
            writePosts(readPosts().filter { !it.read || it.starred || it.publishedAt >= thresholdMs })
            refreshLatest()
        }

        override suspend fun deleteForFeed(feedId: String) {
            writePosts(readPosts().filter { it.feedId != feedId })
            refreshLatest()
        }

        override suspend fun clear() {
            writePosts(emptyList())
        }

        private fun filterAll(query: RssPostsQuery): List<RssPostModel> =
            readPosts()
                .filter { query.feedId == null || it.feedId == query.feedId }
                .filter { !query.unreadOnly || !it.read }
                .filter { !query.starredOnly || it.starred }
                .sortedByDescending { it.publishedAt }
    }

    private inner class SavedAccounts : RssSavedAccountStore {

        override suspend fun saved(): List<RssSavedAccount> {
            val rawValue = raw(KEY_SAVED) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RssSavedAccount.serializer()), rawValue)
            }.getOrDefault(emptyList())
        }

        override suspend fun upsert(account: RssSavedAccount) {
            val rows = saved().filterNot { it.id == account.id } + account
            write(KEY_SAVED, json.encodeToString(ListSerializer(RssSavedAccount.serializer()), rows.sortedByDescending { it.lastUsedAt }))
        }

        override suspend fun delete(id: String) {
            write(KEY_SAVED, json.encodeToString(ListSerializer(RssSavedAccount.serializer()), saved().filterNot { it.id == id }))
        }
    }

    private inner class Account : RssAccountStore {

        override suspend fun get(): RssAccountModel? {
            val rawValue = raw(KEY_ACCOUNT) ?: return null
            return runCatching { json.decodeFromString(AccountDoc.serializer(), rawValue) }
                .getOrNull()
                ?.let { RssAccountModel(mode = RssSyncMode.valueOf(it.mode), serverUrl = it.serverUrl, token = it.token, lastEntryId = it.lastEntryId, lastSyncedAt = it.lastSyncedAt) }
        }

        override suspend fun set(account: RssAccountModel?) {
            if (account == null) {
                write(KEY_ACCOUNT, json.encodeToString(AccountDoc.serializer(), AccountDoc()))
            } else {
                write(
                    KEY_ACCOUNT,
                    json.encodeToString(
                        AccountDoc.serializer(),
                        AccountDoc(account.mode.name, account.serverUrl, account.token, account.lastEntryId, account.lastSyncedAt),
                    ),
                )
            }
        }
    }

    private companion object {
        const val KEY_FEEDS = "voicebook.rss.feeds"
        const val KEY_POSTS = "voicebook.rss.posts"
        const val KEY_ACCOUNT = "voicebook.rss.account"
        const val KEY_SAVED = "voicebook.rss.savedAccounts"
        const val MaxPosts = 300
    }
}
