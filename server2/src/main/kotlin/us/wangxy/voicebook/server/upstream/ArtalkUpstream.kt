package us.wangxy.voicebook.server.upstream

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.server.ServerConfig

/**
 * Artalk 通道。
 *
 * Artalk 的 SSO 是**令牌交换**型（不是授权码登录）：拿 IdP 签给 App 的 access token
 * 调 `POST /api/v2/sso/exchange` 换 Artalk 自己的 JWT，之后所有 Artalk API 用该 JWT。
 *
 * JWT 按用户缓存（Artalk 侧有效期数小时），过期自动重换。
 */
class ArtalkUpstream(
    private val config: ServerConfig,
    private val client: HttpClient,
) {
    private val log = LoggerFactory.getLogger(ArtalkUpstream::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private data class CachedToken(val token: String, val expiresAtMillis: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedToken>()

    /** 拿（或换）某用户对应的 Artalk JWT */
    suspend fun tokenFor(oidcAccessToken: String, cacheKey: String): String {
        cache[cacheKey]?.let { cached ->
            // 留 5 分钟安全边界
            if (System.currentTimeMillis() < cached.expiresAtMillis - 5 * 60_000) return cached.token
        }

        val resp = client.post("${config.artalkUrl}/api/v2/sso/exchange") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"${oidcAccessToken.replace("\"", "\\\"")}"}""")
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            log.warn("Artalk sso/exchange 失败 HTTP ${resp.status.value}: ${text.take(200)}")
            error("Artalk 登录失败（HTTP ${resp.status.value}）")
        }
        val obj = json.parseToJsonElement(text).jsonObject
        val token = obj["token"]?.jsonPrimitive?.content ?: error("Artalk 未返回 token")

        // exp 是秒级时间戳；解不出来就给 1 小时
        val exp = decodeExpSeconds(token) ?: (System.currentTimeMillis() / 1000 + 3600)
        cache[cacheKey] = CachedToken(token, exp * 1000)
        log.info("已为 $cacheKey 换取 Artalk JWT")
        return token
    }

    /** 不解签、只看 payload 的 exp（Artalk JWT 是本服务信任的，无需验签） */
    private fun decodeExpSeconds(jwt: String): Long? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val decoded = java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
        val obj = json.parseToJsonElement(String(decoded)).jsonObject
        (obj["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
    }.getOrNull()

    /** 代理一个 Artalk API 请求（自动带上该用户的 JWT） */
    suspend fun proxy(call: ApplicationCall, oidcAccessToken: String, username: String, pathWithQuery: String) {
        val token = tokenFor(oidcAccessToken, username)
        call.relay(
            client = client,
            targetUrl = "${config.artalkUrl}$pathWithQuery",
            injectHeaders = mapOf("Authorization" to "Bearer $token"),
        )
    }

    /** 让 Artalk 用该用户身份建号（幂等；Artalk 在 exchange 时就已 JIT 建号） */
    suspend fun ensureUser(oidcAccessToken: String, cacheKey: String): JsonObject? = runCatching {
        val token = tokenFor(oidcAccessToken, cacheKey)
        val resp = client.post("${config.artalkUrl}/api/v2/user/access_token") {
            header("Authorization", "Bearer $token")
        }
        if (resp.status == HttpStatusCode.OK) json.parseToJsonElement(resp.bodyAsText()).jsonObject else null
    }.getOrNull()
}
