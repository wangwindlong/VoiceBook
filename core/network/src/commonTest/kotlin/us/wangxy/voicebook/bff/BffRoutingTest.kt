package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.api.calibreServer
import us.wangxy.voicebook.rss.MinifluxApi
import us.wangxy.voicebook.rss.MinifluxCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BFF = "https://bff.test:8462"

private class FakeSession(user: String?, private val token: String? = user?.let { "tok-$it" }) : BffSession {
    override fun baseUrl() = BFF
    override val signedInUser: StateFlow<String?> = MutableStateFlow(user)
    override suspend fun accessToken() = token
    override fun currentAccessToken() = token
}

private class Recorder(private val reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val client = HttpClient(MockEngine { request ->
        requests += request
        reply(request)
    })
}

private fun MockRequestHandleScope.jsonReply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

class BffRoutingTest {

    @Test
    fun calibreServerExistsOnlyWhileSignedIn() {
        assertNull(FakeSession(null).calibreServer())
        val server = FakeSession("alice").calibreServer()!!
        assertTrue(server.viaBff)
        assertEquals(BFF, server.baseUrl)
    }

    @Test
    fun signedInShelfUsesBffCatalogWithBearer() = runTest {
        val recorder = Recorder {
            jsonReply(
                """{"items":[{"id":7,"title":"三体","authors":["刘慈欣"],"hasCover":true,"formats":["PDF","EPUB"]}],
                   "total":21,"offset":20,"limit":20}""",
            )
        }
        val session = FakeSession("alice")
        val api = CalibreWebApi(recorder.client, session)
        val server = session.calibreServer()!!

        val feed = api.newest(server, offset = 20)

        val request = recorder.requests.single()
        assertEquals("$BFF/api/calibre/books?offset=20&limit=20", request.url.toString())
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
        val entry = feed.entries.single()
        assertEquals(7, entry.bookId)
        assertEquals("刘慈欣", entry.author)
        assertEquals("/api/calibre/books/7/file/EPUB", entry.epubHref)
        assertEquals("EPUB", api.formatFromHref(entry.epubHref))
        assertEquals("$BFF/api/calibre/books/7/cover", api.coverUrl(server, entry))
        assertEquals("Bearer tok-alice", api.coverAuthHeader(server))
        assertNull(feed.nextOffset, "21 of 21 books seen")
    }

    @Test
    fun bffSearchAndHistoryDownloadUseBffRoutes() = runTest {
        val recorder = Recorder { request ->
            if (request.url.encodedPath.endsWith("/file/PDF")) respond(byteArrayOf(1, 2, 3)) else jsonReply("""{"items":[],"total":0,"offset":0,"limit":20}""")
        }
        val session = FakeSession("alice")
        val api = CalibreWebApi(recorder.client, session)
        val server = session.calibreServer()!!

        api.search(server, "三体")
        val bytes = api.downloadBook(server, bookId = 9, href = "", format = "pdf")

        assertEquals("三体", recorder.requests[0].url.parameters["q"])
        assertEquals("$BFF/api/calibre/books/9/file/PDF", recorder.requests[1].url.toString())
        assertEquals(3, bytes.size)
    }

    @Test
    fun directCalibreKeepsOpdsAndBasicAuth() = runTest {
        val recorder = Recorder { respond("<feed/>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml")) }
        val api = CalibreWebApi(recorder.client, FakeSession("alice"))
        val server = CalibreServer("http://cwa.local:8083", "bob", "pw")

        api.newest(server)

        val request = recorder.requests.single()
        assertEquals("http://cwa.local:8083/opds/new?offset=0", request.url.toString())
        assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
    }

    @Test
    fun historyCoverFollowsCurrentBackend() {
        val api = CalibreWebApi(HttpClient(MockEngine { respond("") }), FakeSession("alice"))
        val bff = CalibreServer(BFF, viaBff = true)
        val cwa = CalibreServer("http://cwa.local:8083")
        assertEquals("$BFF/api/calibre/books/5/cover", api.coverUrlForBook(bff, 5, "http://cwa.local:8083/opds/cover/5"))
        assertEquals("http://cwa.local:8083/opds/cover/5", api.coverUrlForBook(cwa, 5, "$BFF/api/calibre/books/5/cover"))
        assertEquals("$BFF/api/calibre/books/5/cover", api.coverUrlForBook(bff, 5, "$BFF/api/calibre/books/5/cover"))
    }

    @Test
    fun minifluxGoesThroughBffWhenSignedIn() = runTest {
        val recorder = Recorder { jsonReply("[]") }
        val api = MinifluxApi(recorder.client, FakeSession("alice")) { MinifluxCredentials("http://mf.local", "own-token") }

        api.feeds()

        val request = recorder.requests.single()
        assertEquals("$BFF/api/miniflux/feeds", request.url.toString())
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["X-Auth-Token"])
    }

    @Test
    fun minifluxFallsBackToOwnServerWhenSignedOut() = runTest {
        val recorder = Recorder { jsonReply("[]") }
        val api = MinifluxApi(recorder.client, FakeSession(null)) { MinifluxCredentials("http://mf.local/", "own-token") }

        api.feeds()

        val request = recorder.requests.single()
        assertEquals("http://mf.local/v1/feeds", request.url.toString())
        assertEquals("own-token", request.headers["X-Auth-Token"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun minifluxDialogTestsTypedCredentialsEvenWhenSignedIn() = runTest {
        val recorder = Recorder { jsonReply("{}") }
        val api = MinifluxApi(recorder.client, FakeSession("alice"))

        assertTrue(api.me(MinifluxCredentials("http://mf.local", "typed")))
        assertEquals("http://mf.local/v1/me", recorder.requests.single().url.toString())
    }

    @Test
    fun artalkRequiresSessionAndSendsBearer() = runTest {
        val recorder = Recorder { jsonReply("""{"comments":[],"count":0}""") }

        val error = assertFailsWith<BffApiException> { ArtalkApi(recorder.client, FakeSession(null)).comments("/book/1") }
        assertEquals(401, error.status)
        assertTrue(recorder.requests.isEmpty())

        ArtalkApi(recorder.client, FakeSession("alice")).comments("/book/1", sortBy = "date_desc")
        val request = recorder.requests.single()
        assertEquals("/api/artalk/comments", request.url.encodedPath)
        assertEquals("/book/1", request.url.parameters["page_key"])
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
    }
}
