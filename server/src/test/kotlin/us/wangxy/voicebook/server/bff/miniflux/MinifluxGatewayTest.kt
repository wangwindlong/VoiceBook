package us.wangxy.voicebook.server.bff.miniflux

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.config.MinifluxConfig
import us.wangxy.voicebook.server.bff.json
import us.wangxy.voicebook.server.bff.upstreamHttpClient
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinifluxGatewayTest {
    private val config = MinifluxConfig("http://miniflux", "admin-key", "s".repeat(32))

    private class Recorded(val method: HttpMethod, val path: String, val auth: String?, val body: String)

    private fun gateway(users: String, userCallStatus: () -> HttpStatusCode = { HttpStatusCode.OK }): Pair<MinifluxGateway, MutableList<Recorded>> {
        val log = mutableListOf<Recorded>()
        val engine = MockEngine { req ->
            val auth = req.headers["X-Auth-Token"] ?: req.headers[HttpHeaders.Authorization]
            log += Recorded(req.method, req.url.encodedPath, auth, String(req.body.toByteArray()))
            when {
                req.url.encodedPath == "/v1/users" && req.method == HttpMethod.Get -> json(users)
                req.url.encodedPath.startsWith("/v1/users") -> json("""{"id":7}""", HttpStatusCode.Created)
                else -> json("""{"total":0,"entries":[]}""", userCallStatus())
            }
        }
        return MinifluxGateway(upstreamHttpClient(engine), config) to log
    }

    @Test
    fun missingSharedUserIsCreatedWithDerivedPassword() = runTest {
        val (gw, log) = gateway("""[{"id":1,"username":"admin"}]""")
        gw.call(HttpMethod.Get, "entries", "status=unread")

        val create = log.single { it.method == HttpMethod.Post }
        assertEquals("admin-key", create.auth)
        val body = BffJson.parseToJsonElement(create.body).jsonObject
        assertEquals("voicebook-shared", body["username"]!!.jsonPrimitive.content)
        assertEquals(gw.passwordFor("voicebook-shared"), body["password"]!!.jsonPrimitive.content)

        val userCall = log.last()
        assertEquals("/v1/entries", userCall.path)
        val basic = "Basic " + Base64.getEncoder().encodeToString("voicebook-shared:${gw.passwordFor("voicebook-shared")}".toByteArray())
        assertEquals(basic, userCall.auth)
    }

    @Test
    fun existingUserIsUpdatedWithUsernameIncluded() = runTest {
        val (gw, log) = gateway("""[{"id":7,"username":"voicebook-shared"}]""")
        gw.ensureUser()
        val update = log.single { it.method == HttpMethod.Put }
        assertEquals("/v1/users/7", update.path)
        assertEquals("voicebook-shared", BffJson.parseToJsonElement(update.body).jsonObject["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun unauthorizedCallReprovisionsOnce() = runTest {
        var first = true
        val (gw, log) = gateway("""[{"id":7,"username":"voicebook-shared"}]""") {
            if (first) { first = false; HttpStatusCode.Unauthorized } else HttpStatusCode.OK
        }
        val result = gw.call(HttpMethod.Get, "feeds")
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(2, log.count { it.method == HttpMethod.Put })
        assertTrue(log.count { it.path == "/v1/feeds" } == 2)
    }
}
