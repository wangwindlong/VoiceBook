package us.wangxy.voicebook.server.bff.miniflux

import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.config.MinifluxConfig
import us.wangxy.voicebook.server.bff.hmacSha256Hex

class UpstreamResponse(val status: HttpStatusCode, val contentType: ContentType?, val body: ByteArray)

/**
 * Talks to Miniflux as ONE shared account ([MinifluxConfig.sharedUser]). Every BFF user's
 * subscriptions live under it, so a feed is subscribed and fetched once no matter how many users
 * follow it; per-user subscription / read / starred state is kept by [UserRssStore].
 *
 * Miniflux's auth-proxy header only works for web sessions; the REST API accepts nothing but
 * Basic auth or X-Auth-Token. So the BFF manages the shared user with the admin API key and gives
 * it a password derived from [MinifluxConfig.passwordSecret], then calls the API as that user.
 */
class MinifluxGateway(
    private val http: HttpClient,
    private val config: MinifluxConfig,
) {
    private val log = LoggerFactory.getLogger(MinifluxGateway::class.java)
    private val provisionLock = Mutex()
    @Volatile private var provisioned = false

    val sharedUser: String get() = config.sharedUser

    fun passwordFor(username: String): String = hmacSha256Hex(config.passwordSecret, "miniflux:$username")

    /** Creates the shared Miniflux user if missing, otherwise resets its password to the derived one. */
    suspend fun ensureUser(username: String = config.sharedUser) {
        provisionLock.withLock {
            val existingId = findUserId(username)
            val password = passwordFor(username)
            val response = if (existingId == null) {
                adminCall(HttpMethod.Post, "/v1/users", buildJsonObject {
                    put("username", username)
                    put("password", password)
                    put("is_admin", false)
                }.toString())
            } else {
                // Miniflux rejects PUT /v1/users/{id} without username, even when unchanged.
                adminCall(HttpMethod.Put, "/v1/users/$existingId", buildJsonObject {
                    put("username", username)
                    put("password", password)
                }.toString())
            }
            if (!response.status.isSuccess()) {
                throw BffException.upstream("miniflux", "Miniflux 建号/同步失败 HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            log.info("miniflux user {} {}", username, if (existingId == null) "created" else "synced")
            if (username == config.sharedUser) provisioned = true
        }
    }

    /** Miniflux gives every user its own "All" category, and feed creation requires one of theirs. */
    suspend fun sharedCategoryId(): Long? {
        val response = call(HttpMethod.Get, "/categories")
        if (!response.status.isSuccess()) return null
        return runCatching {
            BffJson.parseToJsonElement(response.body.decodeToString()).jsonArray
                .firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.long
        }.getOrNull()
    }

    /** Calls `/v1/<path>` as the shared account; one transparent re-provision on 401. */
    suspend fun call(
        method: HttpMethod,
        path: String,
        query: String = "",
        body: ByteArray? = null,
        bodyType: ContentType? = null,
    ): UpstreamResponse {
        if (!provisioned) ensureUser()
        var response = userCall(config.sharedUser, method, path, query, body, bodyType)
        if (response.status == HttpStatusCode.Unauthorized) {
            provisioned = false
            ensureUser()
            response = userCall(config.sharedUser, method, path, query, body, bodyType)
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    private suspend fun findUserId(username: String): Long? {
        val response = adminCall(HttpMethod.Get, "/v1/users")
        if (!response.status.isSuccess()) {
            throw BffException.upstream("miniflux", "Miniflux 管理员 token 无效或无权限 HTTP ${response.status.value}")
        }
        return BffJson.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["username"]?.jsonPrimitive?.content == username }
            ?.getValue("id")?.jsonPrimitive?.long
    }

    private suspend fun adminCall(method: HttpMethod, path: String, json: String? = null): HttpResponse = send {
        http.request(config.url + path) {
            this.method = method
            header("X-Auth-Token", config.adminToken)
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }
    }

    private suspend fun userCall(
        username: String,
        method: HttpMethod,
        path: String,
        query: String,
        body: ByteArray?,
        bodyType: ContentType?,
    ): HttpResponse = send {
        val url = config.url + "/v1/" + path.trimStart('/') + if (query.isNotEmpty()) "?$query" else ""
        http.request(url) {
            this.method = method
            basicAuth(username, passwordFor(username))
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            if (body != null && body.isNotEmpty()) {
                setBody(ByteArrayContent(body, bodyType ?: ContentType.Application.Json))
            }
        }
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffException.upstream("miniflux", "无法连接 Miniflux", e)
    }
}
