package us.wangxy.voicebook.server.upstream

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes

/**
 * 透明反向代理：把当前请求原样转给上游，再把响应回写。
 *
 * 三个内容源（Miniflux / Artalk / calibre）都复用这一段，差别只在 [injectHeaders]
 * 里注入什么鉴权：
 *  - Miniflux → X-Forwarded-User: <用户名>（免密，配合其 AUTH_PROXY_HEADER）
 *  - Artalk   → Authorization: Bearer <Artalk JWT>（由 sso/exchange 换来）
 *  - calibre  → Authorization: Basic <共享账号>（OPDS）
 *
 * 用 respondBytes 回写，保证 XML/图片等非 JSON 响应不被 ContentNegotiation 处理。
 */
suspend fun ApplicationCall.relay(
    client: HttpClient,
    targetUrl: String,
    injectHeaders: Map<String, String> = emptyMap(),
) {
    val incoming = this
    val method: HttpMethod = incoming.request.httpMethod
    val isBodyless = method == HttpMethod.Get || method == HttpMethod.Head

    val contentTypeHeader = incoming.request.headers[HttpHeaders.ContentType]
    val acceptHeader = incoming.request.headers[HttpHeaders.Accept]
    val body: ByteArray? = if (isBodyless) null else incoming.receive<ByteArray>()

    val upstream = client.request(targetUrl) {
        this.method = method
        injectHeaders.forEach { (name, value) -> header(name, value) }
        if (contentTypeHeader != null) header(HttpHeaders.ContentType, contentTypeHeader)
        if (acceptHeader != null) header(HttpHeaders.Accept, acceptHeader)
        if (body != null) setBody(body)
    }

    val bytes = upstream.bodyAsBytes()
    val contentType = runCatching {
        upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
    }.getOrNull()

    respondBytes(bytes, contentType, upstream.status)
}
