package us.wangxy.voicebook.rss

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import us.wangxy.voicebook.bff.BffSession

/** A Miniflux server reached directly with the user's own API token (signed-out mode). */
data class MinifluxCredentials(val serverUrl: String, val token: String)

/**
 * News (资讯) client for Miniflux, routed per request:
 *
 *  - signed in: the BFF's Miniflux channel. The BFF authenticates the caller with the account's
 *    access token and proxies to Miniflux as the matching Miniflux user, so auth is
 *    `Authorization: Bearer <access token>` and paths are `/api/miniflux/<x>` (the BFF adds the
 *    version prefix itself and answers 404 "不支持的 Miniflux 接口" for unknown paths);
 *  - signed out: the Miniflux server the user configured, with its own `X-Auth-Token` under
 *    `/v1/<x>` — credentials come from [directCredentials].
 *
 * Responses are parsed as raw JsonElement so upstream field additions never break the app.
 */
class MinifluxApi(
    private val client: HttpClient,
    private val session: BffSession,
    private val directCredentials: suspend () -> MinifluxCredentials? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Miniflux requires a category of the calling user when creating a feed; cached per backend. */
    private var cachedCategory: Pair<String, Long>? = null

    private class Target(val url: String, val authHeader: Pair<String, String>)

    private suspend fun target(path: String, direct: MinifluxCredentials?): Target {
        val relative = path.trimStart('/')
        val credentials = direct ?: run {
            session.accessToken()?.let { token ->
                return Target(session.baseUrl().trimEnd('/') + "/api/miniflux/" + relative, HttpHeaders.Authorization to "Bearer $token")
            }
            directCredentials() ?: throw RssHttpException(401, "未登录统一账号，也没有配置 Miniflux 账号")
        }
        return Target(credentials.serverUrl.trimEnd('/') + "/v1/" + relative, "X-Auth-Token" to credentials.token)
    }

    private suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        jsonBody: Boolean = false,
        direct: MinifluxCredentials? = null,
    ): String {
        val target = target(path, direct)
        val response = client.request(target.url) {
            this.method = io.ktor.http.HttpMethod.parse(method)
            header(target.authHeader.first, target.authHeader.second)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            if (body != null) {
                if (jsonBody) contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) {
            throw RssHttpException(response.status.value, "资讯服务请求失败 HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    /** GET me — cheap validity probe; [direct] tests credentials typed into the account dialog. */
    suspend fun me(direct: MinifluxCredentials? = null): Boolean = try {
        call("GET", "me", direct = direct)
        true
    } catch (e: RssHttpException) {
        if (e.code == 401 || e.code == 403) false else throw e
    }

    /** GET /feeds → list of feed objects (id, feed_url, site_url, title). */
    suspend fun feeds(): List<JsonObject> =
        json.parseToJsonElement(call("GET", "feeds")).jsonArray.map { it.jsonObject }

    /** GET /categories → the calling user's categories (each user gets its own set). */
    suspend fun categories(): List<JsonObject> =
        json.parseToJsonElement(call("GET", "categories")).jsonArray.map { it.jsonObject }

    /** GET /entries with query params (after_entry_id, status, starred, order, ...). */
    suspend fun entries(query: Map<String, String>): JsonObject {
        val qs = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return json.parseToJsonElement(call("GET", "entries?$qs")).jsonObject
    }

    /** PUT /entries — bulk status change (read/unread). */
    suspend fun markEntries(ids: List<String>, status: String): Boolean {
        val payload = """{"entry_ids": [${ids.joinToString(",")}], "status": "$status"}"""
        return try {
            call("PUT", "entries", payload, jsonBody = true)
            true
        } catch (e: RssHttpException) {
            false
        }
    }

    /** PUT /entries/{id}/bookmark — toggle starred. */
    suspend fun toggleStarred(id: String): Boolean = try {
        call("PUT", "entries/$id/bookmark")
        true
    } catch (e: RssHttpException) {
        false
    }

    /**
     * POST /feeds → id of the new feed (null on failure).
     *
     * The category is resolved from the user's own list: Miniflux scopes categories per user, so
     * a hard-coded id (the previous `categoryId: Long = 1`) fails with
     * "This category does not exist or does not belong to this user." for every real account.
     */
    suspend fun createFeed(feedUrl: String): Long? {
        val categoryId = resolveCategoryId() ?: return null
        val payload = """{"feed_url": "$feedUrl", "category_id": $categoryId}"""
        return try {
            call("POST", "feeds", payload, jsonBody = true).trim().trim('"').toLongOrNull()
        } catch (e: RssHttpException) {
            null
        }
    }

    /** DELETE /feeds/{id}. */
    suspend fun deleteFeed(feedId: Long): Boolean = try {
        call("DELETE", "feeds/$feedId")
        true
    } catch (e: RssHttpException) {
        false
    }

    /**
     * GET /feeds/{id}/refresh — force an immediate fetch instead of waiting for the
     * server's polling cycle. Miniflux has no bulk variant, so callers loop over feeds.
     */
    suspend fun refreshFeed(feedId: Long): Boolean = try {
        call("PUT", "feeds/$feedId/refresh")
        true
    } catch (e: RssHttpException) {
        false
    }

    private suspend fun resolveCategoryId(): Long? {
        val backend = target("categories", null).url + "|" + session.signedInUser.value
        cachedCategory?.takeIf { it.first == backend }?.let { return it.second }
        val id = categories().firstOrNull()?.long("id") ?: return null
        cachedCategory = backend to id
        return id
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
