@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.rss

import kotlin.time.Clock
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary

/** Strategy that pulls remote RSS state into the local stores. */
interface RssSyncCoordinator {
    /** Returns true when new posts were stored. */
    suspend fun sync(): Boolean
}

/** Plain HTTP polling: fetch every stored feed URL, parse, merge read-safe. */
class LocalSyncCoordinator(
    private val fetcher: FeedTextFetcher,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
) : RssSyncCoordinator {

    override suspend fun sync(): Boolean {
        initializer.awaitReady()
        var hasNew = false
        val feeds = library.rssFeeds.all()
        val now = Clock.System.now().toEpochMilliseconds()
        for (feed in feeds) {
            try {
                val parsed = RssParser.parse(fetcher.fetchFeedText(feed.feedUrl))
                val posts = parsed.items.map { item ->
                    RssPostModel(
                        id = postKey(feed.id, item.guid),
                        feedId = feed.id,
                        feedTitle = feed.title.ifBlank { parsed.title },
                        title = item.title,
                        link = item.link,
                        publishedAt = item.publishedAt,
                        summary = item.summary,
                        contentHtml = item.contentHtml,
                        imageUrl = item.imageUrl,
                        audioUrl = item.audioUrl,
                    )
                }
                if (posts.isNotEmpty()) {
                    library.rssPosts.upsertAll(posts)
                    hasNew = true
                }
                library.rssFeeds.upsert(feed.copy(title = parsed.title.ifBlank { feed.title }, lastSyncedAt = now))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One bad feed must not block the rest; the error surfaces via the count of staleness.
            }
        }
        return hasNew
    }

    companion object {
        /** Feed-scoped key so identical guids from different feeds never collide. */
        fun postKey(feedId: String, guid: String): String = "$feedId:$guid"
    }
}
