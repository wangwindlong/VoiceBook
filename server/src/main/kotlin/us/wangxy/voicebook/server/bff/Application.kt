package us.wangxy.voicebook.server.bff

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import us.wangxy.voicebook.bff.contract.ApiError
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.server.bff.auth.BffPrincipal
import us.wangxy.voicebook.server.bff.config.BffConfig
import us.wangxy.voicebook.server.bff.routes.LOGIN_LIMIT
import us.wangxy.voicebook.server.bff.routes.PASSWORD_LIMIT
import us.wangxy.voicebook.server.bff.routes.clientIp
import us.wangxy.voicebook.server.bff.routes.REGISTER_LIMIT
import us.wangxy.voicebook.server.bff.routes.artalkRoutes
import us.wangxy.voicebook.server.bff.routes.authRoutes
import us.wangxy.voicebook.server.bff.routes.calibreRoutes
import us.wangxy.voicebook.server.bff.routes.clientKey
import us.wangxy.voicebook.server.bff.routes.minifluxRoutes
import kotlin.time.Duration.Companion.minutes

const val AUTH_OIDC = "oidc"

fun main() {
    val config = BffConfig.fromEnv()
    val services = BffServices.create(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        bffModule(services)
        monitor.subscribe(ApplicationStopped) { services.close() }
    }.start(wait = true)
}

fun Application.bffModule(services: BffServices) {
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != BffRoutes.HEALTH }
    }
    install(ContentNegotiation) { json(BffJson) }
    install(StatusPages) {
        exception<BffException> { call, e ->
            if (e.status.value >= 500) call.application.log.warn("{} {}: {}", e.code, call.request.path(), e.message, e.cause)
            call.respond(e.status, ApiError(e.code, e.message))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", e.message ?: "请求格式错误"))
        }
        exception<Throwable> { call, e ->
            call.application.log.error("unhandled {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL", "服务器内部错误"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ApiError("RATE_LIMITED", "操作过于频繁，请稍后再试"))
        }
    }
    install(RateLimit) {
        register(REGISTER_LIMIT) {
            rateLimiter(limit = services.security.registerPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientKey() }
        }
        register(PASSWORD_LIMIT) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call -> call.clientKey() }
        }
        register(LOGIN_LIMIT) {
            rateLimiter(limit = services.security.loginPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
    }
    val corsOrigins = services.security.corsAllowedOrigins
    if (corsOrigins.isNotEmpty()) {
        install(CORS) {
            corsOrigins.map(::Url).forEach { origin ->
                val host = if (origin.specifiedPort != 0 && origin.specifiedPort != origin.protocol.defaultPort) {
                    "${origin.host}:${origin.specifiedPort}"
                } else {
                    origin.host
                }
                allowHost(host, schemes = listOf(origin.protocol.name))
            }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
        }
    }
    install(Authentication) {
        bearer(AUTH_OIDC) {
            realm = "voicebook-bff"
            authenticate { credential ->
                services.verifier.verify(credential.token)?.let { BffPrincipal(it, credential.token) }
            }
        }
    }

    routing {
        get(BffRoutes.HEALTH) { call.respond(mapOf("status" to "ok")) }
        authRoutes(services)
        authenticate(AUTH_OIDC) {
            minifluxRoutes(services.miniflux, services.rss)
            calibreRoutes(services)
            artalkRoutes(services.artalk)
        }
    }
}
