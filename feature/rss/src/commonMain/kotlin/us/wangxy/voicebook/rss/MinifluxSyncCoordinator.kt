@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.rss

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.rss.RssPostsFilter
import us.wangxy.voicebook.rss.long
import us.wangxy.voicebook.rss.str
import us.wangxy.voicebook.rss.RssSyncMode
import kotlin.time.Clock

/** Miniflux bridge: refresh feeds, then fetch article pages on demand as the user scrolls. */
class MinifluxSyncCoordinator(
    private val api: MinifluxApi,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
) : RssSyncCoordinator {

    override suspend fun sync(): Boolean {
        initializer.awaitReady()
        val account = library.rssAccount.get()
        if (account == null || account.mode != RssSyncMode.Miniflux) return false

        // Refresh only the subscription list. Article pages are fetched by the
        // PagingSource as the user opens the screen and scrolls.
        for (feed in api.feeds()) {
            val id = feed.long("id")?.toString() ?: continue
            val model = RssFeedModel(
                id = feedKey(id),
                title = feed.str("title").orEmpty(),
                link = feed.str("site_url").orEmpty(),
                siteUrl = feed.str("site_url").orEmpty(),
                feedUrl = feed.str("feed_url").orEmpty(),
                imageUrl = null,
                lastSyncedAt = Clock.System.now().toEpochMilliseconds(),
                seedColor = null,
            )
            library.rssFeeds.upsert(model)
        }

        library.rssAccount.set(
            account.copy(lastSyncedAt = Clock.System.now().toEpochMilliseconds()),
        )
        return false
    }

    /** Fetch a single server page when the local article pager reaches it. */
    suspend fun loadPage(limit: Int, offset: Int, query: RssListQuery): List<RssPostModel> {
        val parameters = buildMap {
            put("order", "id")
            put("direction", "desc")
            put("limit", limit.toString())
            put("offset", offset.toString())
            if (query.filter == RssPostsFilter.Unread) put("status", "unread")
            if (query.filter == RssPostsFilter.Starred) put("starred", "true")
            query.feedId?.removePrefix("mf:")?.toLongOrNull()?.let { put("feed_id", it.toString()) }
            query.searchText?.takeIf { it.isNotBlank() }?.let { put("search", it) }
        }
        val titles = library.rssFeeds.all().associate { it.id to it.title }
        return jsonArraySafe(api.entries(parameters)).mapNotNull { entry ->
            val id = entry.long("id")?.toString() ?: return@mapNotNull null
            val feedId = feedKey(entry.long("feed_id")?.toString() ?: "0")
            RssPostModel(
                id = id,
                feedId = feedId,
                feedTitle = titles[feedId].orEmpty(),
                title = entry.str("title").orEmpty(),
                link = entry.str("url").orEmpty(),
                publishedAt = parseIsoOrNow(entry.str("published_at")),
                summary = RssParser.htmlToText(entry.str("summary").orEmpty()).take(SummaryLimit),
                contentHtml = entry.str("content").orEmpty(),
                imageUrl = null,
                read = entry.str("status") != "unread",
                starred = entry.str("starred").toBoolean(),
                audioUrl = entry.str("enclosure_url"),
            )
        }.also { library.rssPosts.upsertAll(it) }
    }

    /** Mark server unread entries read in bounded batches without materializing the full list. */
    suspend fun markAllRead() {
        var cursor: String? = null
        while (true) {
            val query = buildMap {
                put("status", "unread")
                put("order", "id")
                put("direction", "asc")
                put("limit", RssRepository.MinifluxBatchSize.toString())
                cursor?.let { put("after_entry_id", it) }
            }
            val entries = jsonArraySafe(api.entries(query))
            if (entries.isEmpty()) break
            val ids = entries.mapNotNull { it.long("id")?.toString() }
            if (ids.isEmpty() || !api.markEntries(ids, "read")) break
            library.rssPosts.setRead(ids, true)
            cursor = ids.maxByOrNull { it.toLongOrNull() ?: 0L }
            if (entries.size < RssRepository.MinifluxBatchSize) break
        }
    }

    private fun parseIsoOrNow(raw: String?): Long =
        raw?.let { runCatching { RssParser.parseDate(it) }.getOrNull() } ?: Clock.System.now().toEpochMilliseconds()

    private fun feedKey(id: String): String = "mf:$id"

    private companion object {
        const val SummaryLimit = 400
    }

    private fun jsonArraySafe(payload: kotlinx.serialization.json.JsonObject): List<kotlinx.serialization.json.JsonObject> =
        (payload["entries"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .orEmpty()
}
