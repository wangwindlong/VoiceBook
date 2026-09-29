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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap

class UpstreamResponse(val status: HttpStatusCode, val contentType: ContentType?, val body: ByteArray)

/**
 * Miniflux's auth-proxy header only works for web sessions; the REST API accepts nothing but
 * Basic auth or X-Auth-Token. So the BFF manages each user with the admin API key and gives it
 * a password derived from [MinifluxConfig.passwordSecret], then calls the API as that user.
 * Nothing is persisted: after a restart (or secret rotation) users are re-provisioned lazily.
 */
class MinifluxGateway(
    private val http: HttpClient,
    private val config: MinifluxConfig,
) {
    private val log = LoggerFactory.getLogger(MinifluxGateway::class.java)
    private val provisioned = ConcurrentHashMap.newKeySet<String>()
    private val provisionLocks = ConcurrentHashMap<String, Mutex>()
    /** Owns the post-registration default-feed subscriptions (see [subscribeDefaultFeedsInBackground]). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun passwordFor(username: String): String = hmacSha256Hex(config.passwordSecret, "miniflux:$username")

    /** Creates the Miniflux user if missing, otherwise resets its password to the derived one. */
    suspend fun ensureUser(username: String) {
        provisionLocks.getOrPut(username) { Mutex() }.withLock {
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
            provisioned += username
        }
    }

    /**
     * Fire-and-forget variant for registration.
     *
     * Miniflux's `POST /feeds` blocks on the feed's *first* fetch, so subscribing to a slow
     * upstream inside the register call made nginx time out with 504. Registration therefore
     * returns immediately and the subscriptions land a few seconds later.
     */
    fun subscribeDefaultFeedsInBackground(username: String) {
        if (config.defaultFeeds.isEmpty()) return
        background.launch {
            runCatching { subscribeDefaultFeeds(username) }
                .onFailure { log.warn("background default-feed subscription failed for {}", username, it) }
        }
    }

    /**
     * Subscribes a newly provisioned user to [MinifluxConfig.defaultFeeds].
     *
     * Runs the feeds concurrently and tolerates per-feed failures, because Miniflux's
     * `POST /feeds` blocks on that feed's first fetch: a slow upstream can exceed the HTTP
     * client timeout, and a serial loop would abandon every remaining feed when one blows up.
     *
     * Idempotent (Miniflux answers 409 for a feed the user already has).
     *
     * Note this is per-user by design: Miniflux stores feeds unique on (user_id, feed_url) and
     * refreshes every row on its own schedule, so N users × M default feeds costs N×M fetches.
     */
    suspend fun subscribeDefaultFeeds(username: String) {
        val feeds = config.defaultFeeds
        if (feeds.isEmpty()) return
        val categoryId = firstCategoryId(username)
        if (categoryId == null) {
            log.warn("miniflux user {} has no category; skipping default feeds", username)
            return
        }
        coroutineScope {
            feeds.map { feedUrl ->
                async { subscribeOne(username, feedUrl, categoryId) }
            }.awaitAll()
        }
    }

    private suspend fun subscribeOne(username: String, feedUrl: String, categoryId: Long) {
        val payload = buildJsonObject {
            put("feed_url", feedUrl)
            put("category_id", categoryId)
        }.toString()
        try {
            val response = call(
                username = username,
                method = HttpMethod.Post,
                path = "/feeds",
                body = payload.toByteArray(Charsets.UTF_8),
                bodyType = ContentType.Application.Json,
            )
            when {
                response.status.isSuccess() ->
                    log.info("miniflux default feed subscribed for {}: {}", username, feedUrl)
                // 409: the user already has this feed (re-provisioning an existing account).
                response.status == HttpStatusCode.Conflict -> Unit
                else -> log.warn(
                    "miniflux default feed rejected for {} ({}): {}",
                    username, feedUrl, response.body.decodeToString().take(160),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("miniflux default feed failed for {} ({}): {}", username, feedUrl, e.message)
        }
    }

    /** Miniflux gives every user its own "All" category, and feed creation requires one of theirs. */
    private suspend fun firstCategoryId(username: String): Long? {
        val response = call(username, HttpMethod.Get, "/categories")
        if (!response.status.isSuccess()) return null
        return runCatching {
            BffJson.parseToJsonElement(response.body.decodeToString()).jsonArray
                .firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.long
        }.getOrNull()
    }

    /** Calls `/v1/<path>` as [username]; one transparent re-provision on 401. */
    suspend fun call(
        username: String,
        method: HttpMethod,
        path: String,
        query: String = "",
        body: ByteArray? = null,
        bodyType: ContentType? = null,
    ): UpstreamResponse {
        if (username !in provisioned) ensureUser(username)
        var response = userCall(username, method, path, query, body, bodyType)
        if (response.status == HttpStatusCode.Unauthorized) {
            provisioned -= username
            ensureUser(username)
            response = userCall(username, method, path, query, body, bodyType)
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
