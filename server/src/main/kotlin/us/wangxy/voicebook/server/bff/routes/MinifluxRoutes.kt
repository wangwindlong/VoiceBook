package us.wangxy.voicebook.server.bff.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.miniflux.MinifluxGateway

/** First path segments of the Miniflux API a regular user may reach; user admin endpoints are excluded. */
private val ALLOWED_ROOTS = setOf("me", "entries", "feeds", "categories", "icons", "discover", "enclosures")
private val BODY_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)

fun Route.minifluxRoutes(gateway: MinifluxGateway) {
    route(BffRoutes.MINIFLUX) {
        put("/entries/{id}/read") {
            val id = call.longPath("id")
            val body = """{"entry_ids":[$id],"status":"read"}""".toByteArray()
            call.respondUpstream(
                gateway.call(call.bffPrincipal.username, HttpMethod.Put, "entries", body = body, bodyType = ContentType.Application.Json),
            )
        }

        route("{path...}") {
            handle {
                val segments = call.parameters.getAll("path").orEmpty()
                if (segments.isEmpty() || segments.first() !in ALLOWED_ROOTS || segments.any { it.isEmpty() || it == "." || it == ".." }) {
                    throw BffException.notFound("不支持的 Miniflux 接口")
                }
                val method = call.request.httpMethod
                val body = if (method in BODY_METHODS) call.receive<ByteArray>() else null
                call.respondUpstream(
                    gateway.call(
                        username = call.bffPrincipal.username,
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
