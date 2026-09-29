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

/**
 * News (资讯) client talking to the BFF's Miniflux channel.
 *
 * The BFF never hands out Miniflux credentials: it authenticates the caller with the account's
 * access token (the same one used everywhere else) and proxies to Miniflux's own API as the
 * matching Miniflux user. Two consequences shape this class:
 *
 *  - auth is `Authorization: Bearer <access token>`, not Miniflux's own `X-Auth-Token`;
 *  - paths are `/api/miniflux/<x>` — the BFF adds the `/v1/` prefix itself, so a request here must
 *    NOT contain it (the BFF answers 404 "不支持的 Miniflux 接口" for unknown paths).
 *
 * Responses are parsed as raw JsonElement so upstream field additions never break the app.
 */
class MinifluxApi(
    private val client: HttpClient,
    private val session: BffSession,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Miniflux requires a category of the calling user when creating a feed; cached per run. */
    private var cachedCategoryId: Long? = null

    private suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        jsonBody: Boolean = false,
    ): String {
        val token = session.accessToken()
            ?: throw RssHttpException(401, "登录已过期，请重新登录")
        val url = session.baseUrl().trimEnd('/') + "/api/miniflux/" + path.trimStart('/')
        val response = client.request(url) {
            this.method = io.ktor.http.HttpMethod.parse(method)
            header(HttpHeaders.Authorization, "Bearer $token")
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

    /** GET /me — cheap access-token validity probe. */
    suspend fun me(): Boolean = try {
        call("GET", "me")
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
        cachedCategoryId?.let { return it }
        val id = categories().firstOrNull()?.long("id") ?: return null
        cachedCategoryId = id
        return id
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
