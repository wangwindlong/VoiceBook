package us.wangxy.voicebook.data.rss

import kotlinx.coroutines.flow.Flow
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssSavedAccount

/** Optional filters shared by page/count/search. */
data class RssPostsQuery(
    val feedId: String? = null,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
)

interface RssFeedStore {
    /** 账号切换时清空全部订阅源（各自的文章级联清空）。 */
    suspend fun clearAll()


    suspend fun upsert(feed: RssFeedModel)
    suspend fun all(): List<RssFeedModel>
    suspend fun get(id: String): RssFeedModel?
    suspend fun delete(id: String)
    suspend fun updateSeedColor(id: String, seedColor: Int?)
}

interface RssPostStore {
    suspend fun clear()


    /** Latest posts feed (home timeline), newest first. */
    fun observeLatest(): Flow<List<RssPostModel>>

    /** insertOrIgnore + metadata refresh: local read/starred flags survive re-syncs. */
    suspend fun upsertAll(posts: List<RssPostModel>)
    suspend fun page(limit: Int, offset: Int, query: RssPostsQuery): List<RssPostModel>
    suspend fun count(query: RssPostsQuery): Int
    suspend fun get(id: String): RssPostModel?

    suspend fun setRead(ids: List<String>, read: Boolean)
    suspend fun markAllRead()
    suspend fun setStarred(id: String, starred: Boolean)
    suspend fun setAudioProgress(id: String, positionMs: Long, durationMs: Long)
    suspend fun updateSeedColor(id: String, seedColor: Int?)

    /** Posts with images but no seed color yet, newest first (theme backfill). */
    suspend fun postsNeedingSeedColor(limit: Int): List<RssPostModel>

    /** Full-text search; on web this degrades to a substring scan. */
    suspend fun search(text: String, limit: Int, offset: Int): List<RssPostModel>

    suspend fun deleteReadBefore(thresholdMs: Long)
    suspend fun deleteForFeed(feedId: String)
}

/** Single-row sync account (Local by default; Miniflux when configured). */
interface RssAccountStore {
    suspend fun get(): RssAccountModel?
    suspend fun set(account: RssAccountModel?)
}

/** 已保存的 RSS 账号列表（多账号记录与切换）。 */
interface RssSavedAccountStore {
    suspend fun saved(): List<RssSavedAccount>
    suspend fun upsert(account: RssSavedAccount)
    suspend fun delete(id: String)
}
