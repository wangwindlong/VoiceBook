package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import us.wangxy.voicebook.bff.contract.CommentCreateRequest
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.api.calibreServer
import us.wangxy.voicebook.rss.MinifluxApi
import us.wangxy.voicebook.rss.MinifluxCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    }) {
        // 真实客户端装了 ContentNegotiation。缺了它，setBody(<@Serializable DTO>) 会在
        // 发出前抛 IllegalStateException("Fail to prepare request body for sending")。
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
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
    fun directCalibreSavesReaderBookmarkWithBasicAuth() = runTest {
        val recorder = Recorder { respond("", HttpStatusCode.NoContent) }
        val api = CalibreWebApi(recorder.client, FakeSession(null))
        api.saveBookmark(CalibreServer("http://cwa.local:8083", "bob", "pw"), 7, "epub", "3:42")

        val request = recorder.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://cwa.local:8083/ajax/bookmark/7/EPUB", request.url.toString())
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
    fun signedOutMinifluxDoesNotUseLegacyCredentials() = runTest {
        val recorder=Recorder { jsonReply("[]") }
        val api=MinifluxApi(recorder.client,FakeSession(null)) { MinifluxCredentials("http://mf.local","secret") }
        assertFailsWith<us.wangxy.voicebook.rss.RssHttpException> { api.feeds() }
        assertTrue(recorder.requests.isEmpty())
    }
    @Test
    fun directMinifluxProbeDoesNotBypassBff() = runTest {
        val recorder=Recorder { jsonReply("{}") }
        assertEquals(false,MinifluxApi(recorder.client,FakeSession("alice")).me(MinifluxCredentials("http://mf.local","secret")))
        assertTrue(recorder.requests.isEmpty())
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

    @Test
    fun artalkPostSurfacesCaptchaRequirement() = runTest {
        val recorder = Recorder {
            jsonReply(
                """{"need_captcha":true,"img_data":"data:image/png;base64,AAA","msg":"需要验证码"}""",
                HttpStatusCode.Forbidden,
            )
        }

        val result = ArtalkApi(recorder.client, FakeSession("alice"))
            .post(CommentCreateRequest(pageKey = "/calibre/book/1", content = "hi"))

        val request = recorder.requests.single()
        assertEquals("/api/artalk/comments", request.url.encodedPath)
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
        // 403 不能被 bffCall 抛掉：img_data 是弹验证码的唯一依据
        assertTrue(result is CommentPostResult.CaptchaRequired)
        assertEquals("data:image/png;base64,AAA", result.imgData)
        assertEquals("需要验证码", result.message)
    }

    @Test
    fun artalkPostReturnsCommentOnSuccess() = runTest {
        val recorder = Recorder { jsonReply("""{"id":9,"nick":"alice","content":"hi"}""") }

        val result = ArtalkApi(recorder.client, FakeSession("alice"))
            .post(CommentCreateRequest(pageKey = "/calibre/book/1", content = "hi"))

        assertTrue(result is CommentPostResult.Posted)
        assertEquals("9", (result.comment["id"] as JsonPrimitive).content)
    }

    @Test
    fun artalkPostFailsWhenForbiddenForOtherReasons() = runTest {
        val recorder = Recorder { jsonReply("""{"msg":"评论被拒"}""", HttpStatusCode.Forbidden) }

        val error = assertFailsWith<BffApiException> {
            ArtalkApi(recorder.client, FakeSession("alice"))
                .post(CommentCreateRequest(pageKey = "/calibre/book/1", content = "hi"))
        }

        assertEquals(403, error.status)
        assertEquals("评论被拒", error.message)
    }

    @Test
    fun artalkVerifyCaptchaReportsNewImageOnWrongAnswer() = runTest {
        val recorder = Recorder {
            jsonReply("""{"msg":"验证码错误","img_data":"data:image/png;base64,BBB"}""", HttpStatusCode.Forbidden)
        }

        val result = ArtalkApi(recorder.client, FakeSession("alice")).verifyCaptcha("0000")

        val request = recorder.requests.single()
        assertEquals("/api/artalk/captcha/verify", request.url.encodedPath)
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
        // 答错时 Artalk 会换新图：客户端直接替换显示，不必再取一次
        assertTrue(result is CaptchaVerifyResult.Wrong)
        assertEquals("data:image/png;base64,BBB", result.imgData)
    }

    @Test
    fun artalkVerifyCaptchaSucceedsAndCaptchaFetchUsesBffRoute() = runTest {
        val verify = Recorder { jsonReply("""{"msg":"Success"}""") }

        assertEquals(CaptchaVerifyResult.Verified, ArtalkApi(verify.client, FakeSession("alice")).verifyCaptcha("1234"))
        assertEquals("/api/artalk/captcha/verify", verify.requests.single().url.encodedPath)

        val fetch = Recorder { jsonReply("""{"img_data":"data:image/png;base64,CCC"}""") }
        val body = ArtalkApi(fetch.client, FakeSession("alice")).captcha()
        assertEquals("/api/artalk/captcha", fetch.requests.single().url.encodedPath)
        assertEquals("data:image/png;base64,CCC", (body["img_data"] as JsonPrimitive).content)
    }

    @Test
    fun artalkCaptchaNeedsSignedInSession() = runTest {
        val recorder = Recorder { jsonReply("""{"img_data":"data:image/png;base64,CCC"}""") }

        val error = assertFailsWith<BffApiException> { ArtalkApi(recorder.client, FakeSession(null)).captcha() }

        assertEquals(401, error.status)
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun artalkVoteStatusParsesIsUp() = runTest {
        val recorder = Recorder {
            // 线上实测的真实形状：扁平、没有 data 外壳
            jsonReply("""{"up":3,"down":1,"is_up":true,"is_down":false}""")
        }

        val state = ArtalkApi(recorder.client, FakeSession("alice")).voteStatus(9)

        val request = recorder.requests.single()
        assertEquals("/api/artalk/votes/comment/9", request.url.encodedPath)
        assertEquals("Bearer tok-alice", request.headers[HttpHeaders.Authorization])
        assertEquals(3, state.up)
        assertEquals(1, state.down)
        assertTrue(state.isUp, "is_up=true 解析为已赞")
        assertTrue(!state.isDown)
    }

    @Test
    fun artalkVoteUpPostsEmptyBodyAndReturnsNewState() = runTest {
        val recorder = Recorder {
            jsonReply("""{"up":4,"down":1,"is_up":true,"is_down":false}""")
        }

        val result = ArtalkApi(recorder.client, FakeSession("alice")).voteUp(9)

        val request = recorder.requests.single()
        assertEquals("/api/artalk/votes/comment/9/up", request.url.encodedPath)
        assertEquals(HttpMethod.Post, request.method)
        // 请求体 {} 即可：身份由 BFF 补
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("{}", body)
        val done = assertIs<VoteResult.Done>(result)
        assertEquals(4, done.state.up)
        assertTrue(done.state.isUp, "点赞后服务端返回的新状态 is_up=true")
    }

    @Test
    fun artalkVoteStatusToleratesDataWrapper() = runTest {
        // 容错分支：万一哪天响应多包一层 data，也不能解析成 0（历史写法会这么给）
        val recorder = Recorder { jsonReply("""{"data":{"up":7,"is_up":true}}""") }

        val state = ArtalkApi(recorder.client, FakeSession("alice")).voteStatus(9)

        assertEquals(7, state.up)
        assertTrue(state.isUp)
    }

    @Test
    fun artalkVoteUpSurfacesCaptchaRequirement() = runTest {
        val recorder = Recorder {
            jsonReply(
                """{"need_captcha":true,"img_data":"data:image/png;base64,AAA","msg":"需要验证码"}""",
                HttpStatusCode.Forbidden,
            )
        }

        val result = ArtalkApi(recorder.client, FakeSession("alice")).voteUp(9)

        // 与发评论一样，403 验证码是正常分支：img_data 要带给 UI 显示
        val captcha = assertIs<VoteResult.CaptchaRequired>(result)
        assertEquals("data:image/png;base64,AAA", captcha.imgData)
        assertEquals("需要验证码", captcha.message)
    }

    @Test
    fun artalkVoteNeedsSignedInSession() = runTest {
        val recorder = Recorder { jsonReply("""{"data":{"up":1,"is_up":false}}""") }

        val error = assertFailsWith<BffApiException> { ArtalkApi(recorder.client, FakeSession(null)).voteUp(9) }

        assertEquals(401, error.status)
        assertEquals("NOT_SIGNED_IN", error.code)
        assertTrue(recorder.requests.isEmpty())
    }
}
