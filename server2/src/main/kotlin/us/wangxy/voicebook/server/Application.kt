package us.wangxy.voicebook.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json as serverJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import us.wangxy.voicebook.server.auth.configureOidcAuth
import us.wangxy.voicebook.server.lldap.LdapPasswordService
import us.wangxy.voicebook.server.lldap.LldapGraphQlClient
import us.wangxy.voicebook.server.model.ErrorResponse
import us.wangxy.voicebook.server.routes.authRoutes
import us.wangxy.voicebook.server.routes.contentRoutes
import us.wangxy.voicebook.server.upstream.ArtalkUpstream
import us.wangxy.voicebook.server.upstream.CalibreUpstream
import us.wangxy.voicebook.server.upstream.MinifluxUpstream

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: ServerConfig) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ---------- 出站 HTTP 客户端（调 LLDAP 与三个内容源）----------
    val upstreamClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(ClientContentNegotiation) { json(json) }
    }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { upstreamClient.close() }

    // ---------- 插件 ----------
    install(ContentNegotiation) { serverJson(json) }

    // nginx 在前面：用 X-Forwarded-* 还原真实来源，否则日志里全是 127.0.0.1
    install(ForwardedHeaders)
    install(XForwardedHeaders)

    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/healthz") }
    }

    install(StatusPages) {
        exception<IllegalStateException> { call, cause ->
            call.application.log.warn("请求处理失败: ${cause.message}")
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("upstream_error", cause.message ?: "上游服务调用失败"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("未处理异常 ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", cause.message ?: "服务器内部错误"),
            )
        }
    }

    // ---------- 认证 ----------
    configureOidcAuth(config, upstreamClient)

    // ---------- 依赖 ----------
    val lldap = LldapGraphQlClient(config, upstreamClient)
    val passwords = LdapPasswordService(config)
    val miniflux = MinifluxUpstream(config, upstreamClient)
    val artalk = ArtalkUpstream(config, upstreamClient)
    val calibre = CalibreUpstream(config, upstreamClient)

    log.info("VoiceBook BFF 启动：issuer=${config.issuer} miniflux=${config.minifluxUrl} " +
        "artalk=${config.artalkUrl} calibre=${config.calibreUrl} lldap=${config.ldapSummary()}")

    // ---------- 路由 ----------
    routing {
        get("/healthz") { call.respondText("ok") }
        authRoutes(config, lldap, passwords)
        contentRoutes(miniflux, artalk, calibre)
    }
}

/** 日志里只露 LLDAP 的地址，不露管理员 DN/密码 */
private fun ServerConfig.ldapSummary() = "${lldapHttpUrl} (ldap ${lldapLdapUrl})"
