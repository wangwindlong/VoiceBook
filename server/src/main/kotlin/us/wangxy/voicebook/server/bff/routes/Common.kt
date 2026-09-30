package us.wangxy.voicebook.server.bff.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respondBytes
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.auth.BffPrincipal
import us.wangxy.voicebook.server.bff.miniflux.UpstreamResponse

val REGISTER_LIMIT = RateLimitName("register")
val PASSWORD_LIMIT = RateLimitName("password")
val LOGIN_LIMIT = RateLimitName("login")

/** 发评论限频（BFF 侧硬上限；与 Artalk 自己的验证码门槛独立计数）。 */
val COMMENT_LIMIT = RateLimitName("comment")

val ApplicationCall.bffPrincipal: BffPrincipal
    get() = principal<BffPrincipal>() ?: error("route is not under authenticate(AUTH_OIDC)")

/**
 * Rate-limit key: the user when authenticated, otherwise the client IP. X-Real-IP is trusted
 * because the BFF is only reachable through nginx, which overwrites that header.
 */
fun ApplicationCall.clientKey(): String = principal<BffPrincipal>()?.username ?: clientIp()

fun ApplicationCall.clientIp(): String = request.headers["X-Real-IP"] ?: request.origin.remoteHost

suspend fun ApplicationCall.respondUpstream(response: UpstreamResponse) {
    respondBytes(response.body, response.contentType, response.status)
}

fun ApplicationCall.intParam(name: String, default: Int, range: IntRange): Int {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw BffException.badRequest("参数 $name 不是整数")
    if (value !in range) throw BffException.badRequest("参数 $name 超出范围 $range")
    return value
}

fun ApplicationCall.longPath(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw BffException.badRequest("路径参数 $name 非法")
