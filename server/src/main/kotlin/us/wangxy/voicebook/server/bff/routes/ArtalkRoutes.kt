package us.wangxy.voicebook.server.bff.routes

import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CommentCreateRequest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.artalk.ArtalkGateway

private val SORTS = setOf("date_asc", "date_desc", "vote")

private fun validPageKey(key: String?) = key != null && key.startsWith("/") && key.length <= 512

fun Route.artalkRoutes(gateway: ArtalkGateway) {
    get(BffRoutes.ARTALK_COMMENTS) {
        val pageKey = call.request.queryParameters["page_key"]
        if (!validPageKey(pageKey)) throw BffException.badRequest("page_key 需以 / 开头且不超过 512 字符")
        val sortBy = call.request.queryParameters["sort_by"]?.also {
            if (it !in SORTS) throw BffException.badRequest("sort_by 仅支持 $SORTS")
        }
        call.respondUpstream(
            gateway.listComments(
                pageKey = pageKey!!,
                limit = call.intParam("limit", 20, 1..100),
                offset = call.intParam("offset", 0, 0..Int.MAX_VALUE),
                sortBy = sortBy,
            ),
        )
    }

    post(BffRoutes.ARTALK_COMMENTS) {
        val request = call.receive<CommentCreateRequest>()
        if (!validPageKey(request.pageKey)) throw BffException.badRequest("pageKey 需以 / 开头且不超过 512 字符")
        if (request.content.isBlank() || request.content.length > 5000) throw BffException.badRequest("评论内容为空或超过 5000 字")
        call.respondUpstream(gateway.createComment(call.bffPrincipal, request))
    }
}
