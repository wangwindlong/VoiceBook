package us.wangxy.voicebook.server.bff

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RoutesTest {
    private val upstreamPaths = mutableListOf<String>()
    private var revoked = false

    private val upstream = MockEngine { req -> route(req) }

    private suspend fun MockRequestHandleScope.route(req: HttpRequestData): HttpResponseData {
        upstreamPaths += req.url.encodedPath
        return when {
            req.url.encodedPath == "/api/oidc/userinfo" ->
                if (req.headers[HttpHeaders.Authorization] == "Bearer good" && !revoked) json("""{"preferred_username":"alice","email":"alice@x"}""")
                else json("{}", HttpStatusCode.Unauthorized)
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
            req.url.encodedPath.startsWith("/v1/") -> json("""{"total":1}""")
            req.url.encodedPath == "/api/v2/comments" -> json("""{"comments":[],"count":0}""")
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    private fun services(): BffServices {
        val http = upstreamHttpClient(upstream)
        val calibre = createCalibreFixture()
        val security = SecurityConfig(registrationEnabled = true, registerPerMinute = 100, passwordMinLength = 8)
        val dir = FakeDirectory()
        val oidc = OidcConfig(issuer = "http://auth")
        return BffServices(
            verifier = OidcTokenVerifier(http, oidc),
            oidcLogin = OidcLoginClient(http, oidc) { MockEngine { req -> route(req) } },
            accounts = AccountService(dir, dir, emptyList(), security, emptyMap()),
            miniflux = MinifluxGateway(http, MinifluxConfig("http://miniflux", "k", "s".repeat(32))),
            artalk = ArtalkGateway(http, ArtalkConfig("http://artalk", "VoiceBook")),
            calibreLibrary = CalibreLibrary(calibre),
            calibreProgress = CalibreProgressStore(calibre),
            security = security,
        )
    }

    private fun bff(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { bffModule(services()) }
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
    fun minifluxPassThroughIsWhitelisted() = bff {
        assertEquals(HttpStatusCode.OK, client.get("/api/miniflux/entries?status=unread") { bearerAuth("good") }.status)
        assertContains(upstreamPaths, "/v1/entries")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/miniflux/users") { bearerAuth("good") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/miniflux/entries/5/read") { bearerAuth("good") }.status)
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
}
