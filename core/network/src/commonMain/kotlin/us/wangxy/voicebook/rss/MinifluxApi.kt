package us.wangxy.voicebook.rss

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Thin Miniflux REST client (server API v1, X-Auth-Token auth). Responses are
 * parsed as raw JsonElement so upstream field additions never break the app —
 * the same "parse only what you need" stance Twine's network layer takes.
 */
class MinifluxApi(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(serverUrl: String, token: String, path: String): String {
        val response = client.get(serverUrl.trimEnd('/') + path) {
            header("X-Auth-Token", token)
        }
        if (!response.status.isSuccess()) {
            throw RssHttpException(response.status.value, "Miniflux 请求失败 HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    /** GET /v1/me — cheap token validity probe. */
    suspend fun me(serverUrl: String, token: String): Boolean = try {
        get(serverUrl, token, "/v1/me")
        true
    } catch (e: RssHttpException) {
        if (e.code == 401 || e.code == 403) false else throw e
    }

    /** GET /v1/feeds → list of feed objects (id, feed_url, site_url, title). */
    suspend fun feeds(serverUrl: String, token: String): List<JsonObject> =
        json.parseToJsonElement(get(serverUrl, token, "/v1/feeds")).jsonArray.map { it.jsonObject }

    /** GET /v1/entries with query params (after_entry_id, status, starred, order, ...). */
    suspend fun entries(serverUrl: String, token: String, query: Map<String, String>): JsonObject {
        val qs = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return json.parseToJsonElement(get(serverUrl, token, "/v1/entries?$qs")).jsonObject
    }

    /** PUT /v1/entries — bulk status change (read/unread). */
    suspend fun markEntries(serverUrl: String, token: String, ids: List<String>, status: String): Boolean {
        val response = client.put(serverUrl.trimEnd('/') + "/v1/entries") {
            header("X-Auth-Token", token)
            setBody("""{"entry_ids": [${ids.joinToString(",")}], "status": "$status"}""")
        }
        return response.status.isSuccess()
    }

    /** PUT /v1/entries/{id}/bookmark — toggle starred. */
    suspend fun toggleStarred(serverUrl: String, token: String, id: String): Boolean {
        val response = client.put(serverUrl.trimEnd('/') + "/v1/entries/$id/bookmark") {
            header("X-Auth-Token", token)
        }
        return response.status.isSuccess()
    }

    /** POST /v1/feeds → 201 with the new feed id. */
    suspend fun createFeed(serverUrl: String, token: String, feedUrl: String, categoryId: Long = 1): Long? {
        val response = client.post(serverUrl.trimEnd('/') + "/v1/feeds") {
            header("X-Auth-Token", token)
            setBody("""{"feed_url": "$feedUrl", "category_id": $categoryId}""")
        }
        if (!response.status.isSuccess()) return null
        return response.bodyAsText().trim().trim('"').toLongOrNull()
    }

    suspend fun deleteFeed(serverUrl: String, token: String, feedId: Long): Boolean {
        val response = client.delete(serverUrl.trimEnd('/') + "/v1/feeds/$feedId") {
            header("X-Auth-Token", token)
        }
        return response.status.isSuccess()
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
