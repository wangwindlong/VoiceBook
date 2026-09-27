@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.rss

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.rss.long
import us.wangxy.voicebook.rss.str
import us.wangxy.voicebook.rss.RssSyncMode
import kotlin.time.Clock

/**
 * Miniflux server sync, modeled on Twine's MinifluxSyncCoordinator:
 * token probe → feeds upsert → incremental entries pull (after_entry_id cursor)
 * → read-state reconciliation (local reads pushed, server unread pulled back)
 * → starred pull (local stars are NOT pushed: Miniflux bookmark is a toggle, so a
 * blind push would unstar server-side entries the user starred elsewhere).
 */
class MinifluxSyncCoordinator(
    client: HttpClient,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
) : RssSyncCoordinator {

    private val api = MinifluxApi(client)

    override suspend fun sync(): Boolean {
        initializer.awaitReady()
        val account = library.rssAccount.get()
        if (account == null || account.mode != RssSyncMode.Miniflux) return false
        val serverUrl = account.serverUrl ?: return false
        val token = account.token ?: return false

        var hasNew = false

        // 1. feeds (id-prefixed so they can coexist with local-mode feeds)
        val feedTitleById = HashMap<String, String>()
        for (feed in api.feeds(serverUrl, token)) {
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
            feedTitleById[model.id] = model.title
            library.rssFeeds.upsert(model)
        }

        // 2. incremental entry pull
        var cursor = account.lastEntryId
        while (true) {
            val query = buildMap {
                put("order", "id")
                put("direction", "asc")
                put("limit", "100")
                cursor?.let { put("after_entry_id", it) }
            }
            val payload = api.entries(serverUrl, token, query)
            val list = jsonArraySafe(payload)
            if (list.isEmpty()) break
            var maxId = cursor?.toLongOrNull() ?: 0L
            val posts = ArrayList<RssPostModel>(list.size)
            for (entry in list) {
                val entryId = entry.long("id")?.toString() ?: continue
                maxId = maxOf(maxId, entryId.toLongOrNull() ?: 0L)
                val feedId = feedKey(entry.long("feed_id")?.toString() ?: "0")
                posts += RssPostModel(
                    id = entryId,
                    feedId = feedId,
                    feedTitle = feedTitleById[feedId].orEmpty(),
                    title = entry.str("title").orEmpty(),
                    link = entry.str("url").orEmpty(),
                    publishedAt = parseIsoOrNow(entry.str("published_at")),
                    summary = RssParser.htmlToText(entry.str("summary").orEmpty()).take(400),
                    contentHtml = entry.str("content").orEmpty(),
                    imageUrl = null,
                    audioUrl = entry.str("enclosure_url"),
                )
            }
            if (posts.isNotEmpty()) {
                library.rssPosts.upsertAll(posts)
                hasNew = true
            }
            cursor = maxId.toString()
            if (list.size < 100) break
        }

        // 3. read-state reconciliation: local reads go up, server unread comes down
        val minifluxPosts = library.rssPosts.page(Int.MAX_VALUE.div(2), 0, RssPostsQuery())
        val readLocally = minifluxPosts.filter { it.read }.map { it.id }
        readLocally.chunked(100).forEach { batch ->
            api.markEntries(serverUrl, token, batch, "read")
        }
        val unreadIds = HashSet<String>()
        var offset = 0
        while (true) {
            val payload = api.entries(
                serverUrl, token,
                mapOf("status" to "unread", "order" to "id", "direction" to "asc", "limit" to "100", "offset" to offset.toString()),
            )
            val list = jsonArraySafe(payload)
            if (list.isEmpty()) break
            list.forEach { entry -> entry.long("id")?.let { unreadIds.add(it.toString()) } }
            if (list.size < 100) break
            offset += 100
        }
        val locallyRead = minifluxPosts.filter { it.read }.map { it.id }.toSet()
        val toMarkRead = minifluxPosts.map { it.id }.filter { it !in unreadIds && it !in locallyRead }
        toMarkRead.chunked(500).forEach { library.rssPosts.setRead(it, true) }
        val toMarkUnread = minifluxPosts.map { it.id }.filter { it in unreadIds && it in locallyRead }
        toMarkUnread.chunked(500).forEach { library.rssPosts.setRead(it, false) }

        // 4. starred pull
        offset = 0
        while (true) {
            val payload = api.entries(
                serverUrl, token,
                mapOf("starred" to "true", "order" to "id", "direction" to "asc", "limit" to "100", "offset" to offset.toString()),
            )
            val list = jsonArraySafe(payload)
            if (list.isEmpty()) break
            list.forEach { entry -> entry.long("id")?.let { library.rssPosts.setStarred(it.toString(), true) } }
            if (list.size < 100) break
            offset += 100
        }

        library.rssAccount.set(
            account.copy(lastEntryId = cursor, lastSyncedAt = Clock.System.now().toEpochMilliseconds()),
        )
        return hasNew
    }

    private fun parseIsoOrNow(raw: String?): Long =
        raw?.let { runCatching { RssParser.parseDate(it) }.getOrNull() } ?: Clock.System.now().toEpochMilliseconds()

    private fun feedKey(id: String): String = "mf:$id"

    private fun jsonArraySafe(payload: kotlinx.serialization.json.JsonObject): List<kotlinx.serialization.json.JsonObject> =
        (payload["entries"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .orEmpty()
}
