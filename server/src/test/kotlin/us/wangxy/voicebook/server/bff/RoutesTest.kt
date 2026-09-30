package us.wangxy.voicebook.server.bff

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import us.wangxy.voicebook.server.bff.account.AccountService
import us.wangxy.voicebook.server.bff.account.FakeDirectory
import us.wangxy.voicebook.server.bff.artalk.ArtalkGateway
import us.wangxy.voicebook.server.bff.auth.OidcLoginClient
import us.wangxy.voicebook.server.bff.auth.OidcTokenVerifier
import us.wangxy.voicebook.server.bff.calibre.CalibreLibrary
import us.wangxy.voicebook.server.bff.calibre.CalibreProgressStore
import us.wangxy.voicebook.server.bff.config.ArtalkConfig
import us.wangxy.voicebook.server.bff.config.MinifluxConfig
import us.wangxy.voicebook.server.bff.config.OidcConfig
import us.wangxy.voicebook.server.bff.config.SecurityConfig
import us.wangxy.voicebook.server.bff.miniflux.MinifluxGateway
import us.wangxy.voicebook.server.bff.miniflux.SharedRssService
import us.wangxy.voicebook.server.bff.miniflux.UserRssStore
import io.ktor.http.HttpMethod
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RoutesTest {
    private val upstreamPaths = mutableListOf<String>()
    private var revoked = false
    private var exchangeNeedsCaptcha = false
    private var voteNeedsCaptcha = false

    /** 记录透传给 Artalk 的 X-Forwarded-For 与点赞请求体：断言 IP 透传与身份填充。 */
    private val voteIps = mutableListOf<String>()
    private val votePosts = mutableListOf<String>()
    private val feedPosts = mutableListOf<String>()

    private val upstream = MockEngine { req -> route(req) }

    private suspend fun MockRequestHandleScope.route(req: HttpRequestData): HttpResponseData {
        upstreamPaths += req.url.encodedPath
        return when {
            req.url.encodedPath == "/api/oidc/userinfo" -> when {
                // bob 是第二个账号：用来断言 BFF 给 Artalk 的地址是「按账号」而不是按来源 IP
                req.headers[HttpHeaders.Authorization] == "Bearer bob" -> json("""{"preferred_username":"bob","email":"bob@x"}""")
                req.headers[HttpHeaders.Authorization] == "Bearer good" && !revoked ->
                    json("""{"preferred_username":"alice","email":"alice@x"}""")
                else -> json("{}", HttpStatusCode.Unauthorized)
            }
            req.url.encodedPath == "/api/firstfactor" ->
                if (String(req.body.toByteArray()).contains("\"password\":\"right\"")) json("""{"status":"OK"}""")
                else json("""{"status":"KO"}""", HttpStatusCode.Unauthorized)
            req.url.encodedPath == "/api/oidc/authorization" -> respond(
                "", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "voicebook://oauth2/callback?code=c&state=${req.url.parameters["state"]}"),
            )
            req.url.encodedPath == "/api/oidc/token" -> json("""{"access_token":"good","refresh_token":"rt","expires_in":3600}""")
            req.url.encodedPath == "/api/oidc/revocation" -> { revoked = true; json("{}") }
            req.url.encodedPath == "/v1/users" -> json("""[{"id":2,"username":"alice"}]""")
            req.url.encodedPath.startsWith("/v1/users/") -> json("{}")
            req.url.encodedPath == "/v1/feeds" && req.method == HttpMethod.Post -> {
                feedPosts += String(req.body.toByteArray())
                json("""{"feed_id":2}""", HttpStatusCode.Created)
            }
            req.url.encodedPath == "/v1/feeds" -> json("""[{"id":1,"feed_url":"http://f/rss"}]""")
            req.url.encodedPath == "/v1/categories" -> json("""[{"id":1,"title":"All"}]""")
            req.url.encodedPath == "/v1/entries" -> json(
                """{"total":2,"entries":[{"id":6,"feed_id":1,"status":"unread","starred":false},{"id":5,"feed_id":1,"status":"unread","starred":false},{"id":4,"feed_id":9,"status":"unread","starred":false}]}""",
            )
            req.url.encodedPath.startsWith("/v1/") -> json("""{"total":1}""")
            req.url.encodedPath == "/api/v2/sso/exchange" ->
                // 发评论前 BFF 先用用户的 OIDC access token 换 Artalk JWT（payload 里 exp=4102444800）
                if (exchangeNeedsCaptcha) {
                    // 反垃圾连换票也拦（实测形状）：403 + need_captcha + img_data
                    json(
                        """{"need_captcha":true,"img_data":"data:image/png;base64,EXCHANGE","msg":"需要验证码"}""",
                        HttpStatusCode.Forbidden,
                    )
                } else {
                    json("""{"token":"eyJhbGciOiJub25lIn0.eyJleHAiOjQxMDI0NDQ4MDB9.x"}""")
                }
            req.url.encodedPath == "/api/v2/comments" && req.method == HttpMethod.Post ->
                // 反垃圾拦下时 Artalk 的真实形状：403 + need_captcha + img_data（BFF 必须原样透出）
                json(
                    """{"need_captcha":true,"img_data":"data:image/png;base64,SAMPLE","msg":"需要验证码"}""",
                    HttpStatusCode.Forbidden,
                )
            req.url.encodedPath == "/api/v2/comments" -> json("""{"comments":[],"count":0}""")
            // 点赞是开关语义：同一个 /up 端点，响应里是**操作后的新状态**（is_up=false 即已取消）
            req.url.encodedPath.startsWith("/api/v2/votes/comment/") && req.url.encodedPath.endsWith("/up") -> {
                voteIps += req.headers["X-Forwarded-For"] ?: ""
                votePosts += String(req.body.toByteArray())
                if (voteNeedsCaptcha) {
                    json(
                        """{"need_captcha":true,"img_data":"data:image/png;base64,VOTE","msg":"需要验证码"}""",
                        HttpStatusCode.Forbidden,
                    )
                } else {
                    json("""{"data":{"up":4,"down":0,"is_up":false,"is_down":false}}""")
                }
            }
            req.url.encodedPath.startsWith("/api/v2/votes/comment/") -> {
                voteIps += req.headers["X-Forwarded-For"] ?: ""
                json("""{"data":{"up":5,"down":0,"is_up":true,"is_down":false}}""")
            }
            req.url.encodedPath == "/api/v2/captcha" -> json("""{"img_data":"data:image/png;base64,SAMPLE"}""")
            req.url.encodedPath == "/api/v2/captcha/verify" ->
                if (String(req.body.toByteArray()).contains("\"value\":\"1234\"")) json("""{"msg":"Success"}""")
                else json("""{"msg":"验证码错误","img_data":"data:image/png;base64,RETRY"}""", HttpStatusCode.Forbidden)
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    private fun services(
        security: SecurityConfig = SecurityConfig(registrationEnabled = true, registerPerMinute = 100, passwordMinLength = 8),
    ): BffServices {
        val http = upstreamHttpClient(upstream)
        val calibre = createCalibreFixture()
        val dir = FakeDirectory()
        val oidc = OidcConfig(issuer = "http://auth")
        val miniflux = MinifluxGateway(http, MinifluxConfig("http://miniflux", "k", "s".repeat(32)))
        return BffServices(
            verifier = OidcTokenVerifier(http, oidc),
            oidcLogin = OidcLoginClient(http, oidc) { MockEngine { req -> route(req) } },
            accounts = AccountService(dir, dir, emptyList(), security, emptyMap()),
            miniflux = miniflux,
            rss = SharedRssService(miniflux, UserRssStore(File(Files.createTempDirectory("bff-state").toFile(), "bff.db"))),
            artalk = ArtalkGateway(http, ArtalkConfig("http://artalk", "VoiceBook")),
            calibreLibrary = CalibreLibrary(calibre),
            calibreProgress = CalibreProgressStore(calibre),
            security = security,
        )
    }

    private fun bff(
        security: SecurityConfig = SecurityConfig(registrationEnabled = true, registerPerMinute = 100, passwordMinLength = 8),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { bffModule(services(security)) }
        block()
    }

    @Test
    fun healthIsPublicAndApiRequiresToken() = bff {
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me") { bearerAuth("bad") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/calibre/books") { bearerAuth("bad") }.status)
    }

    @Test
    fun loginRefreshLogoutCycle() = bff {
        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertContains(bad.bodyAsText(), "INVALID_CREDENTIALS")

        val ok = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"Alice","password":"right"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertContains(ok.bodyAsText(), "\"accessToken\":\"good\"")
        assertContains(ok.bodyAsText(), "\"username\":\"alice\"")

        val refreshed = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"rt"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)

        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth("good") }.status)
        val logout = client.post("/api/auth/logout") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"rt"}""")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me") { bearerAuth("good") }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/logout").status)
    }

    @Test
    fun configIsPublic() = bff {
        assertContains(client.get("/api/auth/config").bodyAsText(), "\"registrationEnabled\":true")
    }

    @Test
    fun meReturnsUserinfoClaims() = bff {
        val body = client.get("/api/auth/me") { bearerAuth("good") }.bodyAsText()
        assertContains(body, "\"username\":\"alice\"")
    }

    @Test
    fun registerValidatesAndCreates() = bff {
        val bad = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"x","email":"x@y.z","password":"passw0rd1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertContains(bad.bodyAsText(), "INVALID_USERNAME")

        val ok = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","email":"bob@y.z","password":"passw0rd1"}""")
        }
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    @Test
    fun minifluxIsSharedButStateIsPerUser() = bff {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/miniflux/users") { bearerAuth("good") }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/miniflux/feeds/1/refresh") { bearerAuth("good") }.status)

        // Nothing subscribed yet: no feeds, no entries.
        assertEquals("[]", client.get("/api/miniflux/feeds") { bearerAuth("good") }.bodyAsText())

        // Existing shared feed: only linked, never re-created upstream.
        val existing = client.post("/api/miniflux/feeds") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"feed_url":"http://f/rss"}""")
        }
        assertEquals(HttpStatusCode.Created, existing.status)
        assertContains(existing.bodyAsText(), "\"feed_id\":1")
        assertEquals(0, feedPosts.size)
        assertContains(client.get("/api/miniflux/feeds") { bearerAuth("good") }.bodyAsText(), "http://f/rss")

        // Unknown URL: the shared account subscribes it.
        val created = client.post("/api/miniflux/feeds") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"feed_url":"http://new/rss"}""")
        }
        assertContains(created.bodyAsText(), "\"feed_id\":2")
        assertEquals(1, feedPosts.size)

        // Entries of feed 1 only (feed 9 is not ours), all unread.
        val unread = client.get("/api/miniflux/entries?status=unread") { bearerAuth("good") }.bodyAsText()
        assertContains(unread, "\"id\":5")
        assertContains(unread, "\"id\":6")
        assertEquals(false, unread.contains("\"id\":4"))

        assertEquals(
            HttpStatusCode.NoContent,
            client.put("/api/miniflux/entries") {
                bearerAuth("good")
                contentType(ContentType.Application.Json)
                setBody("""{"entry_ids":[5],"status":"read"}""")
            }.status,
        )
        assertEquals(HttpStatusCode.NoContent, client.put("/api/miniflux/entries/6/bookmark") { bearerAuth("good") }.status)

        val afterRead = client.get("/api/miniflux/entries?status=unread") { bearerAuth("good") }.bodyAsText()
        assertEquals(false, afterRead.contains("\"id\":5"))
        assertContains(afterRead, "\"id\":6")
        val starred = client.get("/api/miniflux/entries?starred=true") { bearerAuth("good") }.bodyAsText()
        assertContains(starred, "\"id\":6")
        assertEquals(false, starred.contains("\"id\":5"))
    }

    @Test
    fun calibreAndArtalkRoutes() = bff {
        val books = client.get("/api/calibre/books?limit=1") { bearerAuth("good") }.bodyAsText()
        assertContains(books, "\"total\":2")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/calibre/books?limit=1000") { bearerAuth("good") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/calibre/books/1/cover") { bearerAuth("good") }.status)

        val put = client.put("/api/calibre/progress/1") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"format":"EPUB","position":"epubcfi(/6/2)","percent":5}""")
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        assertContains(client.get("/api/calibre/progress/1") { bearerAuth("good") }.bodyAsText(), "epubcfi(/6/2)")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/artalk/comments?page_key=nope") { bearerAuth("good") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/artalk/comments?page_key=/calibre/book/1") { bearerAuth("good") }.status)
    }

    @Test
    fun artalkCaptchaIsProxiedBehindTheSameAuthGate() = bff {
        // 与发评论同一个鉴权门：未登录拿不到图
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/artalk/captcha").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/artalk/captcha/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"1234"}""")
            }.status,
        )

        val got = client.get("/api/artalk/captcha") { bearerAuth("good") }
        assertEquals(HttpStatusCode.OK, got.status)
        assertContains(got.bodyAsText(), "img_data")

        // 答错：Artalk 的 403 与新图原样透出
        val wrong = client.post("/api/artalk/captcha/verify") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"value":"9999"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertContains(wrong.bodyAsText(), "RETRY")

        // 空白答案在 BFF 就被挡下，不打上游
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/artalk/captcha/verify") {
                bearerAuth("good")
                contentType(ContentType.Application.Json)
                setBody("""{"value":"   "}""")
            }.status,
        )

        // 答对：200
        val right = client.post("/api/artalk/captcha/verify") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"value":" 1234 "}""")
        }
        assertEquals(HttpStatusCode.OK, right.status)
        assertContains(right.bodyAsText(), "Success")
    }

    @Test
    fun artalkCommentPostPassesCaptchaChallengeThrough() = bff {
        // 反垃圾拦截是**正常分支**：403 + need_captcha + img_data 必须原样透出，
        // 客户端才能拿这张图弹验证码；若这里被包成 200/500 或丢掉 img_data，验证码流程直接瘫痪。
        val blocked = client.post("/api/artalk/comments") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"pageKey":"/calibre/book/1","content":"hi"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertContains(blocked.bodyAsText(), "\"need_captcha\":true")
        assertContains(blocked.bodyAsText(), "data:image/png;base64,SAMPLE")
    }

    @Test
    fun commentPostIsRateLimitedPerAccount() = bff(
        // 限额压到 2 条以便在测试里触发（线上默认 10 条 / 60 分钟）
        SecurityConfig(registrationEnabled = true, registerPerMinute = 100, passwordMinLength = 8, commentRateLimit = 2),
    ) {
        suspend fun postComment(): HttpStatusCode = client.post("/api/artalk/comments") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"pageKey":"/calibre/book/1","content":"hi"}""")
        }.status

        // 被上游验证码拦下的请求**照样计额度**（限流在进入 handler 之前生效）
        assertEquals(HttpStatusCode.Forbidden, postComment())
        assertEquals(HttpStatusCode.Forbidden, postComment())

        val before = upstreamPaths.count { it == "/api/v2/comments" }
        assertEquals(HttpStatusCode.TooManyRequests, postComment())
        // 429 是 BFF 自己发的：这一枪没有落到 Artalk
        assertEquals(before, upstreamPaths.count { it == "/api/v2/comments" })
    }

    @Test
    fun captchaChallengeOnTokenExchangeIsPassedThrough() = bff {
        // 反垃圾也会拦 `/sso/exchange` 换票（它同属那 8 个受保护 POST）。那时必须把 403 + 图
        // 原样透出：包成 502 的话客户端既弹不出验证码，提示语还是误导性的「服务器暂时不可用」。
        exchangeNeedsCaptcha = true

        val blocked = client.post("/api/artalk/comments") {
            bearerAuth("good")
            contentType(ContentType.Application.Json)
            setBody("""{"pageKey":"/calibre/book/1","content":"hi"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertContains(blocked.bodyAsText(), "\"need_captcha\":true")
        assertContains(blocked.bodyAsText(), "data:image/png;base64,EXCHANGE")
    }

    @Test
    fun artalkSeesStablePerAccountAddressInsteadOfClientIp() = bff {
        // Artalk 的「我赞过没有」判定/去重与验证码计数都按来源 IP 记账，所以 BFF 传给它的是
        // **由账号推导的稳定地址**（ArtalkGateway.artalkIp）：同一账号换设备/换网络都是同一个值，
        // 不同账号互不影响。这也是为什么这里**不该**看到真实客户端 IP。
        val status = client.get("/api/artalk/votes/comment/7") {
            bearerAuth("good")
            header("X-Real-IP", "198.51.100.9")
        }
        assertEquals(HttpStatusCode.OK, status.status)
        assertContains(status.bodyAsText(), "\"is_up\":true")
        val aliceAddr = voteIps.last()
        assertTrue(aliceAddr.startsWith("10."), "应为按账号推导的地址，实得 $aliceAddr")
        assertNotEquals("198.51.100.9", aliceAddr, "真实客户端 IP 不该再传给 Artalk")

        // 同一账号换一个来源 IP：地址必须不变 —— 否则「我赞过没有」会随网络变化，切一次网络就重复计票
        val toggled = client.post("/api/artalk/votes/comment/7/up") {
            bearerAuth("good")
            header("X-Real-IP", "203.0.113.7")
        }
        assertEquals(HttpStatusCode.OK, toggled.status)
        assertContains(toggled.bodyAsText(), "\"is_up\":false")
        assertEquals(aliceAddr, voteIps.last())
        // 点赞**不换票**（Artalk 的 /votes 不需要票据），但身份要由 BFF 填好
        assertContains(votePosts.last(), "\"name\":\"alice\"")

        // 另一个账号：地址不同 → 点赞状态与反垃圾额度各自独立（局域网共用一个出口 IP 也不互相干扰）
        val bob = client.get("/api/artalk/votes/comment/7") { bearerAuth("bob") }
        assertEquals(HttpStatusCode.OK, bob.status)
        assertNotEquals(aliceAddr, voteIps.last())

        // 这两条路由在 authenticate(AUTH_OIDC) 块里：未登录 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/artalk/votes/comment/7").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/artalk/votes/comment/7/up").status)
    }

    @Test
    fun voteCaptchaChallengeIsPassedThrough() = bff {
        // 点赞也会被反垃圾守（LimiterGuard 覆盖所有写端点）：403 + 图必须原样交给客户端，
        // 客户端走一次验证码后把同一个点赞请求重发即可。
        voteNeedsCaptcha = true
        val blocked = client.post("/api/artalk/votes/comment/7/up") {
            bearerAuth("good")
            // 带一个真实 IP 头：现在它不会影响 Artalk 的地址（按账号映射），仅确保不因此出错
            header("X-Real-IP", "198.51.100.9")
        }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertContains(blocked.bodyAsText(), "\"need_captcha\":true")
        assertContains(blocked.bodyAsText(), "data:image/png;base64,VOTE")
    }
}
