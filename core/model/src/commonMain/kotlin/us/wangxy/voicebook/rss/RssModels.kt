package us.wangxy.voicebook.rss

import kotlinx.serialization.Serializable

/**
 * RSS domain models shared by the stores (core:data), the sync coordinators and
 * the UI (feature:rss). Pure data so they survive the SQLDelight/web split.
 */
@Serializable
data class RssFeedModel(
    val id: String,
    val title: String,
    /** Article link / html url. */
    val link: String,
    val siteUrl: String,
    val feedUrl: String,
    val imageUrl: String? = null,
    val lastSyncedAt: Long = 0L,
    val seedColor: Int? = null,
)

enum class RssSyncMode { Local, Miniflux }

@Serializable
data class RssAccountModel(
    val mode: RssSyncMode,
    val serverUrl: String? = null,
    val token: String? = null,
    /** Miniflux incremental cursor: id of the newest entry already pulled. */
    val lastEntryId: String? = null,
    val lastSyncedAt: Long = 0L,
)

@Serializable
data class RssPostModel(
    /** guid (RSS) or id (Atom); stable key for read/starred state. */
    val id: String,
    val feedId: String,
    val feedTitle: String = "",
    val title: String,
    val link: String,
    val publishedAt: Long,
    val summary: String,
    val contentHtml: String,
    val imageUrl: String? = null,
    val read: Boolean = false,
    val starred: Boolean = false,
    /** Podcast/attachment audio, from enclosure or media:content. */
    val audioUrl: String? = null,
    val audioPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val seedColor: Int? = null,
) {
    val hasAudio: Boolean get() = !audioUrl.isNullOrBlank()
}

/** 已保存的 RSS 同步账号（多账号切换用）。 */
@Serializable
data class RssSavedAccount(
    val id: String,
    val label: String,
    val mode: RssSyncMode,
    val serverUrl: String? = null,
    val token: String? = null,
    val lastUsedAt: Long = 0L,
)

/** Filter for the article list drawer (Twine's posts-type switch). */
enum class RssPostsFilter { All, Unread, Starred }
