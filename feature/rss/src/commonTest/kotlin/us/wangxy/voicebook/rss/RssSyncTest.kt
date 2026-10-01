@file:OptIn(kotlin.time.ExperimentalTime::class)
package us.wangxy.voicebook.rss

import kotlinx.coroutines.test.runTest
import io.ktor.client.engine.mock.respond
import us.wangxy.voicebook.data.InMemoryLocalLibrary
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.rss.RssPostsQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeReaderStateStore : us.wangxy.voicebook.reader.store.ReaderStateStore {
    var state: us.wangxy.voicebook.reader.store.ReaderState? = null
    override fun load() = state
    override fun save(state: us.wangxy.voicebook.reader.store.ReaderState) {
        this.state = state
    }
}

/** Serves canned feed XML per URL and records fetches. */
private class FakeFetcher(val feeds: MutableMap<String, String>) : FeedTextFetcher {
    val fetched = mutableListOf<String>()
    override suspend fun fetchFeedText(url: String): String {
        fetched += url
        return feeds[url] ?: throw RssHttpException(404, "not found")
    }
}

private fun feedXml(title: String, vararg posts: Triple<String, String, Long>): String {
    val items = posts.joinToString("") { (guid, title2, date) ->
        """
        <item><guid>$guid</guid><title>$title2</title>
        <link>https://example.org/$guid</link>
        <pubDate>${kotlin.time.Instant.fromEpochMilliseconds(date)}</pubDate>
        <description>摘要 $guid</description></item>
        """.trimIndent()
    }
    return "<rss version='2.0'><channel><title>$title</title><link>https://example.org</link>$items</channel></rss>"
}

private suspend fun newLibrary(): LocalLibrary = InMemoryLocalLibrary().also {
    it.server.set(null)
}

class RssSyncTest {

    @Test
    fun independentAccountSurvivesUnifiedLoginAndLogout() = runTest {
        val library = newLibrary()
        val initializer = LibraryInitializer(FakeReaderStateStore(), library)
        val user = kotlinx.coroutines.flow.MutableStateFlow<String?>("alice")
        val session = object : us.wangxy.voicebook.bff.BffSession {
            override fun baseUrl() = "https://bff.test"
            override val signedInUser = user
            override suspend fun accessToken() = user.value?.let { "unified-token" }
            override fun currentAccessToken() = user.value?.let { "unified-token" }
        }
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            requests += request
            respond("[]", io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
        })
        val api = MinifluxApi(client, session) {
            library.rssAccount.get()?.let { MinifluxCredentials(it.serverUrl.orEmpty(), it.token.orEmpty()) }
        }
        val repository = RssRepository(
            client, library, initializer,
            us.wangxy.voicebook.data.theme.SeedColorExtractor(client, us.wangxy.voicebook.reader.api.CalibreWebApi(client, session), library, initializer),
            LocalSyncCoordinator(FakeFetcher(mutableMapOf()), library, initializer),
            MinifluxSyncCoordinator(api, library, initializer), api, session,
        )
        val account = RssAccountModel(mode = RssSyncMode.Miniflux, serverUrl = "https://mf.test", token = "own-token")
        repository.setAccount(account)
        repository.enableUnifiedNews()
        repository.sync()
        assertEquals("own-token", repository.account()?.token)
        assertEquals("https://bff.test/api/miniflux/feeds", requests.last().url.toString())
        assertEquals("Bearer unified-token", requests.last().headers["Authorization"])

        user.value = null
        repository.onSessionChanged(false)
        assertEquals("https://mf.test/v1/feeds", requests.last().url.toString())
        assertEquals("own-token", requests.last().headers["X-Auth-Token"])
        assertEquals(account.serverUrl, repository.account()?.serverUrl)
        assertEquals(account.token, repository.account()?.token)
        client.close()
    }

    @Test
    fun syncStoresPostsAndKeepsReadFlagsOnResync() = runTest {
        val library = newLibrary()
        val initializer = LibraryInitializer(FakeReaderStateStore(), library)
        val feeds = mutableMapOf(
            "https://example.org/feed" to feedXml("示例源", Triple("a", "文章 A", 1700000000000L), Triple("b", "文章 B", 1700000100000L)),
        )
        val fetcher = FakeFetcher(feeds)
        val coordinator = LocalSyncCoordinator(fetcher, library, initializer)

        library.rssFeeds.upsert(RssFeedModel(id = "local:https://example.org/feed", title = "", link = "", siteUrl = "", feedUrl = "https://example.org/feed"))
        coordinator.sync()

        val afterFirst = library.rssPosts.page(10, 0, RssPostsQuery())
        assertEquals(2, afterFirst.size)
        assertTrue(afterFirst.all { it.feedTitle == "示例源" })

        // User reads one post.
        val readId = afterFirst[0].id
        library.rssPosts.setRead(listOf(readId), true)

        // Feed updates the title of the same guid (fresh metadata, same id).
        feeds["https://example.org/feed"] = feedXml("示例源", Triple("a", "文章 A（更新）", 1700000000000L), Triple("b", "文章 B", 1700000100000L))
        coordinator.sync()

        val afterSecond = library.rssPosts.page(10, 0, RssPostsQuery())
        assertEquals(2, afterSecond.size, "re-sync must not duplicate posts")
        assertEquals("文章 A（更新）", afterSecond.first { it.id != readId }.title, "metadata refreshes")
        assertTrue(afterSecond.first { it.id == readId }.read, "read flag survives re-sync")
    }

    @Test
    fun failingFeedDoesNotBlockOthers() = runTest {
        val library = newLibrary()
        val initializer = LibraryInitializer(FakeReaderStateStore(), library)
        val feeds = mutableMapOf(
            "https://bad.example/feed" to "not-xml-at-all",
            "https://good.example/feed" to feedXml("好源", Triple("g1", "好文章", 1700000000000L)),
        )
        val coordinator = LocalSyncCoordinator(FakeFetcher(feeds), library, initializer)
        library.rssFeeds.upsert(RssFeedModel(id = "bad", title = "bad", link = "", siteUrl = "", feedUrl = "https://bad.example/feed"))
        library.rssFeeds.upsert(RssFeedModel(id = "good", title = "good", link = "", siteUrl = "", feedUrl = "https://good.example/feed"))

        coordinator.sync()

        val posts = library.rssPosts.page(10, 0, RssPostsQuery())
        assertEquals(1, posts.size)
        assertEquals("好文章", posts[0].title)
    }

    @Test
    fun deleteForFeedRemovesPosts() = runTest {
        val library = newLibrary()
        val initializer = LibraryInitializer(FakeReaderStateStore(), library)
        val fetcher = FakeFetcher(mutableMapOf("https://example.org/feed" to feedXml("源", Triple("a", "文章", 1700000000000L))))
        val coordinator = LocalSyncCoordinator(fetcher, library, initializer)
        library.rssFeeds.upsert(RssFeedModel(id = "local:https://example.org/feed", title = "", link = "", siteUrl = "", feedUrl = "https://example.org/feed"))
        coordinator.sync()

        library.rssFeeds.delete("local:https://example.org/feed")

        assertEquals(0, library.rssPosts.count(RssPostsQuery()))
        assertTrue(library.rssFeeds.all().isEmpty())
    }
}
