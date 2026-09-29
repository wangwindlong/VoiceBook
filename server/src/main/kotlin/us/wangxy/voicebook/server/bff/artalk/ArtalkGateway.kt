package us.wangxy.voicebook.server.bff.artalk

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import us.wangxy.voicebook.bff.contract.CommentCreateRequest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.auth.BffPrincipal
import us.wangxy.voicebook.server.bff.config.ArtalkConfig
import us.wangxy.voicebook.server.bff.miniflux.UpstreamResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Artalk accepts the user's OIDC access token at `/api/v2/sso/exchange` and returns its own JWT
 * (creating the Artalk user on first use). JWTs are cached per user until shortly before expiry.
 */
class ArtalkGateway(
    private val http: HttpClient,
    private val config: ArtalkConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ArtalkToken(val token: String, val expiresAtSeconds: Long?)

    private val tokens = ConcurrentHashMap<String, ArtalkToken>()

    suspend fun tokenFor(principal: BffPrincipal, forceRefresh: Boolean = false): ArtalkToken {
        if (!forceRefresh) {
            tokens[principal.username]
                ?.takeIf { t -> t.expiresAtSeconds == null || t.expiresAtSeconds * 1000 - REFRESH_MARGIN_MS > clock() }
                ?.let { return it }
        }
        val response = send {
            http.post(config.url + "/api/v2/sso/exchange") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("token", principal.accessToken) }.toString())
            }
        }
        if (!response.status.isSuccess()) {
            throw BffException.upstream("artalk", "Artalk SSO 换票失败 HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        val jwt = (BffJson.parseToJsonElement(response.bodyAsText()).jsonObject["token"] as? JsonPrimitive)?.contentOrNull
            ?: throw BffException.upstream("artalk", "Artalk SSO 响应缺少 token")
        return ArtalkToken(jwt, jwtExpiry(jwt)).also { tokens[principal.username] = it }
    }

    fun forget(username: String) {
        tokens.remove(username)
    }

    suspend fun listComments(pageKey: String, limit: Int, offset: Int, sortBy: String?): UpstreamResponse {
        val response = send {
            http.get(config.url + "/api/v2/comments") {
                parameter("page_key", pageKey)
                parameter("site_name", config.siteName)
                parameter("limit", limit)
                parameter("offset", offset)
                if (sortBy != null) parameter("sort_by", sortBy)
            }
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    suspend fun createComment(principal: BffPrincipal, request: CommentCreateRequest): UpstreamResponse {
        val body = buildJsonObject {
            put("content", request.content)
            put("name", principal.user.displayName ?: principal.username)
            put("email", principal.user.email ?: "${principal.username}@users.voicebook.invalid")
            put("link", "")
            put("rid", request.replyTo ?: 0)
            put("page_key", request.pageKey)
            put("page_title", request.pageTitle ?: "")
            put("site_name", config.siteName)
        }.toString()

        suspend fun post(token: ArtalkToken) = send {
            http.post(config.url + "/api/v2/comments") {
                bearerAuth(token.token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        var response = post(tokenFor(principal))
        if (response.status == HttpStatusCode.Unauthorized) {
            response = post(tokenFor(principal, forceRefresh = true))
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffException.upstream("artalk", "无法连接 Artalk", e)
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L

        fun jwtExpiry(jwt: String): Long? = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(jwt.split('.')[1]))
            (BffJson.parseToJsonElement(payload).jsonObject["exp"] as? JsonPrimitive)?.longOrNull
        }.getOrNull()
    }
}
