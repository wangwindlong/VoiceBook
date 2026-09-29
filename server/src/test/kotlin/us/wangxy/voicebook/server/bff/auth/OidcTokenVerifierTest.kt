package us.wangxy.voicebook.server.bff.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.OidcConfig
import us.wangxy.voicebook.server.bff.json
import us.wangxy.voicebook.server.bff.upstreamHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OidcTokenVerifierTest {
    private val config = OidcConfig(issuer = "https://auth.invalid", cacheTtlSeconds = 60)
    private var now = 0L
    private var calls = 0

    private fun verifier(handler: MockEngine) = OidcTokenVerifier(upstreamHttpClient(handler), config) { now }

    private val engine = MockEngine { request ->
        calls++
        when (request.headers[HttpHeaders.Authorization]) {
            "Bearer good" -> json("""{"sub":"x","preferred_username":"alice","name":"Alice","email":"a@x","groups":["calibre_web"]}""")
            "Bearer broken" -> json("{}", HttpStatusCode.InternalServerError)
            else -> json("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun validTokenIsResolvedAndCached() = runTest {
        val v = verifier(engine)
        val user = v.verify("good")!!
        assertEquals("alice", user.username)
        assertEquals(listOf("calibre_web"), user.groups)
        v.verify("good")
        assertEquals(1, calls)

        now += 61_000
        v.verify("good")
        assertEquals(2, calls)
    }

    @Test
    fun rejectedTokenReturnsNull() = runTest {
        assertNull(verifier(engine).verify("nope"))
        assertNull(verifier(engine).verify(""))
    }

    @Test
    fun upstreamFailureIsNotCachedAsInvalid() = runTest {
        val v = verifier(engine)
        assertFailsWith<BffException> { v.verify("broken") }
        assertFailsWith<BffException> { v.verify("broken") }
        assertEquals(2, calls)
    }
}
