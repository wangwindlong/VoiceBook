package us.wangxy.voicebook.server.bff.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.OidcConfig
import us.wangxy.voicebook.server.bff.json
import us.wangxy.voicebook.server.bff.upstreamHttpClient
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OidcLoginClientTest {
    private val config = OidcConfig(issuer = "https://auth.invalid")
    private val seen = mutableListOf<String>()
    private var challenge: String? = null
    private var authorizationLocation: (HttpRequestData) -> String = { req ->
        "voicebook://oauth2/callback?code=the-code&state=${req.url.parameters["state"]}"
    }
    private var firstFactorStatus = HttpStatusCode.OK

    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { req ->
        seen += req.url.encodedPath
        when (req.url.encodedPath) {
            "/api/firstfactor" -> respond(
                """{"status":"OK"}""", firstFactorStatus,
                headersOf(HttpHeaders.SetCookie, "authelia_session=abc; Path=/"),
            )
            "/api/oidc/authorization" -> {
                assertEquals("authelia_session=abc", req.headers[HttpHeaders.Cookie])
                assertTrue(req.url.parameters["state"]!!.length >= 8)
                challenge = req.url.parameters["code_challenge"]
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, authorizationLocation(req)))
            }
            "/api/oidc/token" -> {
                val form = parseQueryString(String(req.body.toByteArray()))
                when (form["grant_type"]) {
                    "authorization_code" -> {
                        assertEquals("the-code", form["code"])
                        val digest = MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray())
                        assertEquals(challenge, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                        json("""{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"bearer"}""")
                    }
                    "refresh_token" ->
                        if (form["refresh_token"] == "rt") json("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600}""")
                        else json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
                    else -> json("{}", HttpStatusCode.BadRequest)
                }
            }
            else -> json("{}")
        }
    }

    private fun client(): OidcLoginClient {
        val engine = MockEngine(handler)
        return OidcLoginClient(upstreamHttpClient(engine), config) { MockEngine(handler) }
    }

    @Test
    fun loginRunsFirstFactorAuthorizationAndPkceExchange() = runTest {
        val tokens = client().login("alice", "pw", "10.0.0.1")
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(listOf("/api/firstfactor", "/api/oidc/authorization", "/api/oidc/token", "/api/logout"), seen)
    }

    @Test
    fun wrongPasswordIsUnauthorized() = runTest {
        firstFactorStatus = HttpStatusCode.Unauthorized
        val e = assertFailsWith<BffException> { client().login("alice", "bad", null) }
        assertEquals("INVALID_CREDENTIALS", e.code)
        assertEquals(HttpStatusCode.Unauthorized, e.status)
    }

    @Test
    fun redirectBackToPortalMeansInteractionRequired() = runTest {
        authorizationLocation = { "https://auth.invalid/?rd=whatever" }
        assertEquals("INTERACTION_REQUIRED", assertFailsWith<BffException> { client().login("alice", "pw", null) }.code)
        assertTrue("/api/logout" in seen)
    }

    @Test
    fun stateMismatchIsRejected() = runTest {
        authorizationLocation = { "voicebook://oauth2/callback?code=x&state=forged-state" }
        assertFailsWith<BffException> { client().login("alice", "pw", null) }
    }

    @Test
    fun refreshRotatesOrExpires() = runTest {
        assertEquals("at2", client().refresh("rt").accessToken)
        assertEquals("SESSION_EXPIRED", assertFailsWith<BffException> { client().refresh("old") }.code)
    }
}
