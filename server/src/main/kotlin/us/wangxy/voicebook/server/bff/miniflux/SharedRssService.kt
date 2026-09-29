package us.wangxy.voicebook.server.bff.miniflux

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Presents the Miniflux API to each user as if they owned a private account, while everything
 * is really stored once under the gateway's shared account:
 *
 *  - feeds: the user only sees feeds they subscribed to; subscribing to a URL the shared account
 *    already has just records the link, otherwise the shared account subscribes (and fetches) it;
 *  - entries: fetched from the shared account, restricted to the user's feeds, with `status` and
 *    `starred` replaced by the user's own state from [UserRssStore].
 */
class SharedRssService(
    private val gateway: MinifluxGateway,
    private val store: UserRssStore,
    private val defaultFeeds: List<String> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(SharedRssService::class.java)
    private val subscribeLocks = ConcurrentHashMap<String, Mutex>()

    // ---- feeds ----

    suspend fun listFeeds(username: String): JsonArray {
        val mine = store.subscribedFeeds(username)
        if (mine.isEmpty()) return JsonArray(emptyList())
        return JsonArray(allFeeds().filter { it.id() in mine })
    }

    suspend fun getFeed(username: String, feedId: Long): JsonObject {
        requireSubscribed(username, feedId)
        return getJson("/feeds/$feedId").jsonObject
    }

    /** Returns the id of the (possibly pre-existing) shared feed now followed by [username]. */
    suspend fun subscribe(username: String, rawUrl: String): Long {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw BffException.badRequest("订阅地址必须以 http:// 或 https:// 开头", "INVALID_FEED_URL")
        }
        val id = subscribeLocks.getOrPut(url.lowercase()) { Mutex() }.withLock { ensureSharedFeed(url) }
        store.subscribe(username, id)
        return id
    }

    /** Subscribes a new user to the configured default feeds; failures are logged per feed. */
    suspend fun subscribeDefaults(username: String) {
        for (url in defaultFeeds) {
            try {
                subscribe(username, url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("default feed {} for {} failed: {}", url, username, e.message)
            }
        }
    }

    /**
     * Drops the user's link. The shared feed is deleted once nobody follows it any more
     * (default feeds are kept), so abandoned subscriptions stop being fetched.
     */
    suspend fun unsubscribe(username: String, feedId: Long) {
        val remaining = store.unsubscribe(username, feedId)
        if (remaining > 0) return
        val response = gateway.call(HttpMethod.Get, "/feeds/$feedId")
        if (!response.status.isSuccess()) return
        val url = BffJson.parseToJsonElement(response.body.decodeToString()).jsonObject["feed_url"]?.jsonPrimitive?.content
        if (url != null && defaultFeeds.any { it.equals(url, ignoreCase = true) }) return
        val deleted = gateway.call(HttpMethod.Delete, "/feeds/$feedId")
        if (!deleted.status.isSuccess()) log.warn("could not delete orphan feed {}: HTTP {}", feedId, deleted.status.value)
    }

    suspend fun refreshFeed(username: String, feedId: Long) {
        requireSubscribed(username, feedId)
        val response = gateway.call(HttpMethod.Put, "/feeds/$feedId/refresh")
        if (!response.status.isSuccess()) {
            throw BffException(response.status, "UPSTREAM_MINIFLUX", "刷新失败 HTTP ${response.status.value}")
        }
    }

    private suspend fun ensureSharedFeed(url: String): Long {
        findFeedByUrl(url)?.let { return it }
        val categoryId = gateway.sharedCategoryId()
            ?: throw BffException.upstream("miniflux", "共享账号没有可用的分类")
        val payload = buildJsonObject {
            put("feed_url", url)
            put("category_id", categoryId)
        }.toString()
        val response = gateway.call(
            HttpMethod.Post, "/feeds", body = payload.toByteArray(Charsets.UTF_8), bodyType = ContentType.Application.Json,
        )
        if (response.status.isSuccess()) {
            val id = runCatching {
                BffJson.parseToJsonElement(response.body.decodeToString()).jsonObject["feed_id"]?.jsonPrimitive?.long
            }.getOrNull()
            if (id != null) return id
        }
        // Someone else created it between our lookup and the POST.
        findFeedByUrl(url)?.let { return it }
        throw BffException(
            if (response.status.isSuccess()) HttpStatusCode.BadGateway else response.status,
            "SUBSCRIBE_FAILED",
            "订阅失败: ${response.body.decodeToString().take(160)}",
        )
    }

    private suspend fun findFeedByUrl(url: String): Long? =
        allFeeds().firstOrNull { it["feed_url"]?.jsonPrimitive?.content?.equals(url, ignoreCase = true) == true }?.id()

    private suspend fun allFeeds(): List<JsonObject> = getJson("/feeds").jsonArray.map { it.jsonObject }

    // ---- entries ----

    /**
     * Miniflux can neither exclude ids nor filter on another user's state, so `status`, `starred`,
     * `limit` and `offset` are applied here: the shared account's entries are scanned in
     * upstream pages until the requested window is filled. `total` is therefore exact only when
     * the scan reached the end; otherwise it is a lower bound.
     */
    suspend fun listEntries(username: String, rawQuery: String): JsonObject {
        val params = parseQueryString(rawQuery)
        val mine = store.subscribedFeeds(username)
        val feedFilter = params["feed_id"]?.toLongOrNull()
        val allowed: Set<Long> = when {
            feedFilter == null -> mine
            feedFilter in mine -> setOf(feedFilter)
            else -> emptySet()
        }
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val skip = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val statuses = params.getAll("status").orEmpty().toSet()
        val onlyStarred = params["starred"]?.let { it.isEmpty() || it == "true" || it == "1" } == true

        if (allowed.isEmpty()) return entriesPage(0, emptyList())

        val passthrough = Parameters.build {
            params.forEach { name, values ->
                if (name !in OWNED_PARAMS) appendAll(name, values)
            }
            // "removed" entries are never shown; read/unread is decided per user below.
            append("status", "read")
            append("status", "unread")
            append("limit", SCAN_PAGE.toString())
        }

        val collected = ArrayList<JsonObject>()
        var matched = 0
        var upstreamOffset = 0
        var exhausted = false
        while (collected.size < limit && upstreamOffset < MAX_SCAN) {
            val query = Parameters.build {
                appendAll(passthrough)
                append("offset", upstreamOffset.toString())
            }.formUrlEncode()
            val page = getJson("/entries", query).jsonObject
            val batch = page["entries"]?.jsonArray.orEmpty().map { it.jsonObject }
            val states = store.states(username, batch.mapNotNull { it.entryId() })
            for (entry in batch) {
                val id = entry.entryId() ?: continue
                if (entry["feed_id"]?.jsonPrimitive?.longOrNull !in allowed) continue
                val state = states[id] ?: UNSEEN
                if (statuses.isNotEmpty() && state.statusName() !in statuses) continue
                if (onlyStarred && !state.starred) continue
                if (matched++ < skip) continue
                if (collected.size < limit) collected += entry.withState(state)
            }
            upstreamOffset += batch.size
            if (batch.size < SCAN_PAGE) {
                exhausted = true
                break
            }
        }
        val total = if (exhausted) matched else maxOf(matched, skip + collected.size + 1)
        return entriesPage(total, collected)
    }

    suspend fun getEntry(username: String, entryId: Long): JsonObject {
        val entry = getJson("/entries/$entryId").jsonObject
        val feedId = entry["feed_id"]?.jsonPrimitive?.longOrNull
        if (feedId == null || !store.isSubscribed(username, feedId)) throw BffException.notFound("文章不存在")
        return entry.withState(store.states(username, listOf(entryId))[entryId] ?: UNSEEN)
    }

    suspend fun markEntries(username: String, entryIds: List<Long>, status: String) {
        when (status) {
            "read" -> store.setRead(username, entryIds, true)
            "unread" -> store.setRead(username, entryIds, false)
            else -> throw BffException.badRequest("不支持的状态 $status")
        }
    }

    suspend fun toggleBookmark(username: String, entryId: Long): Boolean = store.toggleStarred(username, entryId)

    // ---- helpers ----

    private suspend fun requireSubscribed(username: String, feedId: Long) {
        if (!store.isSubscribed(username, feedId)) throw BffException.notFound("未订阅该源")
    }

    private suspend fun getJson(path: String, query: String = ""): JsonElement {
        val response = gateway.call(HttpMethod.Get, path, query)
        if (!response.status.isSuccess()) {
            throw BffException(
                if (response.status == HttpStatusCode.NotFound) response.status else HttpStatusCode.BadGateway,
                "UPSTREAM_MINIFLUX",
                "Miniflux 请求失败 HTTP ${response.status.value}",
            )
        }
        return BffJson.parseToJsonElement(response.body.decodeToString())
    }

    private fun entriesPage(total: Int, entries: List<JsonObject>) = buildJsonObject {
        put("total", total)
        put("entries", JsonArray(entries))
    }

    private fun JsonObject.id(): Long? = this["id"]?.jsonPrimitive?.longOrNull
    private fun JsonObject.entryId(): Long? = id()
    private fun EntryState.statusName() = if (read) "read" else "unread"

    private fun JsonObject.withState(state: EntryState) =
        JsonObject(this + mapOf("status" to JsonPrimitive(state.statusName()), "starred" to JsonPrimitive(state.starred)))

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        const val SCAN_PAGE = 100
        const val MAX_SCAN = 10_000
        val UNSEEN = EntryState(read = false, starred = false)
        val OWNED_PARAMS = setOf("status", "starred", "limit", "offset")
    }
}
