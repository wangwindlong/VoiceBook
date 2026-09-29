package us.wangxy.voicebook.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import us.wangxy.voicebook.server.auth.AUTH_OIDC
import us.wangxy.voicebook.server.auth.voiceBookUser
import us.wangxy.voicebook.server.model.ErrorResponse
import us.wangxy.voicebook.server.upstream.ArtalkUpstream
import us.wangxy.voicebook.server.upstream.CalibreUpstream
import us.wangxy.voicebook.server.upstream.MinifluxUpstream

/**
 * 内容源代理路由。全部要求 `Authorization: Bearer <OIDC access_token>`。
 *
 * App 只认这三个前缀，三个组件对外都不暴露：
 *
 * | App 侧路径 | 上游 |
 * |---|---|
 * | /api/miniflux/xxx | Miniflux 的 /xxx（注入 X-Forwarded-User）|
 * | /api/artalk/xxx | Artalk 的 /api/v2/xxx（换 JWT 后带 Bearer）|
 * | /api/calibre/xxx | calibre 的 /xxx（OPDS，带共享账号 Basic）|
 *
 * 用尾通配 `{...}` 兜住全部子路径，并把常见方法都指向同一个处理函数。
 */
fun Route.contentRoutes(
    miniflux: MinifluxUpstream,
    artalk: ArtalkUpstream,
    calibre: CalibreUpstream,
) {
    authenticate(AUTH_OIDC) {

        route("/api/miniflux/{...}") {
            get { proxyMiniflux(miniflux) }
            post { proxyMiniflux(miniflux) }
            put { proxyMiniflux(miniflux) }
            patch { proxyMiniflux(miniflux) }
            delete { proxyMiniflux(miniflux) }
        }

        route("/api/artalk/{...}") {
            get { proxyArtalk(artalk) }
            post { proxyArtalk(artalk) }
            put { proxyArtalk(artalk) }
            patch { proxyArtalk(artalk) }
            delete { proxyArtalk(artalk) }
        }

        route("/api/calibre/{...}") {
            get { proxyCalibre(calibre) }
            post { proxyCalibre(calibre) }
            put { proxyCalibre(calibre) }
            patch { proxyCalibre(calibre) }
            delete { proxyCalibre(calibre) }
        }
    }
}

private suspend fun RoutingContext.proxyMiniflux(miniflux: MinifluxUpstream) {
    val user = call.voiceBookUser()
    if (user == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "需要登录"))
        return
    }
    val suffix = call.request.uri.removePrefix("/api/miniflux")
    miniflux.proxy(call, user.username, suffix)
}

private suspend fun RoutingContext.proxyArtalk(artalk: ArtalkUpstream) {
    val user = call.voiceBookUser()
    if (user == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "需要登录"))
        return
    }
    val bearer = call.request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()
    if (bearer.isNullOrBlank()) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "缺少访问令牌"))
        return
    }
    val suffix = call.request.uri.removePrefix("/api/artalk")
    artalk.proxy(call, bearer, user.username, "/api/v2$suffix")
}

private suspend fun RoutingContext.proxyCalibre(calibre: CalibreUpstream) {
    val user = call.voiceBookUser()
    if (user == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "需要登录"))
        return
    }
    val suffix = call.request.uri.removePrefix("/api/calibre")
    calibre.proxy(call, suffix)
}
