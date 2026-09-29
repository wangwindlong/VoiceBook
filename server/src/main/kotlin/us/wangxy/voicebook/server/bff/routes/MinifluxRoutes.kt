package us.wangxy.voicebook.server.bff.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.miniflux.MinifluxGateway
import us.wangxy.voicebook.server.bff.miniflux.SharedRssService

/**
 * Read-only Miniflux endpoints that carry nothing user specific and can be proxied as-is from
 * the shared account. Everything that touches feeds, entries or state is handled by
 * [SharedRssService] instead; user admin and other write endpoints are not reachable.
 */
private val SHARED_READ_ROOTS = setOf("me", "categories", "icons", "enclosures")

fun Route.minifluxRoutes(gateway: MinifluxGateway, rss: SharedRssService) {
    route(BffRoutes.MINIFLUX) {
        // ---- feeds (per-user subscriptions over the shared account) ----
        get("/feeds") { call.respondJson(rss.listFeeds(call.bffPrincipal.username)) }
        get("/feeds/{id}") { call.respondJson(rss.getFeed(call.bffPrincipal.username, call.longPath("id"))) }
        post("/feeds") {
            val url = BffJson.parseToJsonElement(call.receiveText()).jsonObject["feed_url"]?.jsonPrimitive?.content
                ?: throw BffException.badRequest("缺少 feed_url")
            val id = rss.subscribe(call.bffPrincipal.username, url)
            call.respondJson(buildJsonObject { put("feed_id", id) }, HttpStatusCode.Created)
        }
        delete("/feeds/{id}") {
            rss.unsubscribe(call.bffPrincipal.username, call.longPath("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        put("/feeds/{id}/refresh") {
            rss.refreshFeed(call.bffPrincipal.username, call.longPath("id"))
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- entries (merged with the user's read / starred state) ----
        get("/entries") { call.respondJson(rss.listEntries(call.bffPrincipal.username, call.request.queryString())) }
        get("/entries/{id}") { call.respondJson(rss.getEntry(call.bffPrincipal.username, call.longPath("id"))) }
        put("/entries") {
            val body = BffJson.parseToJsonElement(call.receiveText()).jsonObject
            val ids = body["entry_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }
                ?: throw BffException.badRequest("缺少 entry_ids")
            val status = body["status"]?.jsonPrimitive?.content ?: throw BffException.badRequest("缺少 status")
            rss.markEntries(call.bffPrincipal.username, ids, status)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/entries/{id}/read") {
            rss.markEntries(call.bffPrincipal.username, listOf(call.longPath("id")), "read")
            call.respond(HttpStatusCode.NoContent)
        }
        put("/entries/{id}/bookmark") {
            rss.toggleBookmark(call.bffPrincipal.username, call.longPath("id"))
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- user-independent reads, and feed discovery ----
        route("{path...}") {
            handle {
                val segments = call.parameters.getAll("path").orEmpty()
                val method = call.request.httpMethod
                val safe = segments.none { it.isEmpty() || it == "." || it == ".." }
                val allowed = safe && segments.isNotEmpty() && (
                    (segments.first() in SHARED_READ_ROOTS && method == HttpMethod.Get) ||
                        (segments.first() == "discover" && method == HttpMethod.Post)
                    )
                if (!allowed) throw BffException.notFound("不支持的 Miniflux 接口")
                val body = if (method == HttpMethod.Post) call.receive<ByteArray>() else null
                call.respondUpstream(
                    gateway.call(
                        method = method,
                        path = segments.joinToString("/") { it.encodeURLPathPart() },
                        query = call.request.queryString(),
                        body = body,
                        bodyType = call.request.contentType().takeIf { it != ContentType.Any },
                    ),
                )
            }
        }
    }
}

private suspend fun ApplicationCall.respondJson(element: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(element.toString(), ContentType.Application.Json, status)
