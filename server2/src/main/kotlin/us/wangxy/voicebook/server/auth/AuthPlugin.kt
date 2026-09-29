package us.wangxy.voicebook.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap
import us.wangxy.voicebook.server.ServerConfig
import us.wangxy.voicebook.server.model.ErrorResponse

/** 认证方案名，路由里用 authenticate(AUTH_OIDC) 引用 */
const val AUTH_OIDC = "oidc"

/**
 * 认证通过后挂在 call 上的用户身份。
 *
 * [username] 是**各组件认的那个用户名**（LLDAP 里的 uid）——Miniflux 的反代头、
 * Artalk 的建号、LLDAP 的组都靠它；[subject] 是 IdP 侧的 UUID，仅作内部标识。
 */
data class VoiceBookPrincipal(
    val subject: String,
    val username: String,
    val email: String?,
    val displayName: String?,
    /** LLDAP 组（需要 IdP 在 userinfo 里给出 groups claim；没有则为空） */
    val groups: List<String> = emptyList(),
) : Principal {
    /** java.security.Principal 要求；用 LLDAP 用户名 */
    override fun getName(): String = username
}

/**
 * 安装 OIDC Bearer 认证。
 *
 * **为什么不用本地 JWKS 验签**：Authelia 签发的 `access_token` 是**不透明 token**
 * （实测约 99 字符、非 JWT；只有 `id_token` 是 JWT）。规范里校验不透明访问令牌的方式
 * 就是拿它去调 `userinfo` 端点——Artalk 的 SSO 也是这么做的。
 *
 * 代价是每个请求要打一次 IdP，因此这里加一层按 token 哈希的短 TTL 缓存。
 */
fun Application.configureOidcAuth(config: ServerConfig, client: HttpClient) {
    val userInfo = UserInfoCache(config, client)

    install(Authentication) {
        bearer(AUTH_OIDC) {
            realm = "voicebook"
            // authenticate 返回 null 时 Ktor 默认回 401（不需要自定义 challenge）
            authenticate { credential -> userInfo.resolve(credential.token) }
        }
    }
}

/** 取当前请求的已认证用户（未认证返回 null） */
fun ApplicationCall.voiceBookUser(): VoiceBookPrincipal? = principal<VoiceBookPrincipal>()

/**
 * access_token → 用户信息，带短 TTL 缓存。
 *
 * 缓存键是 token 的 SHA-256（不保留明文），TTL 60 秒——既避免每请求回源，
 * 又能让「登出/改密后旧 token 失效」在一分钟内传导。
 */
private class UserInfoCache(
    private val config: ServerConfig,
    private val client: HttpClient,
) {
    private val log = LoggerFactory.getLogger(UserInfoCache::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val ttlMillis = 60_000L

    private data class Entry(val principal: VoiceBookPrincipal, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(token: String): VoiceBookPrincipal? {
        val key = sha256(token)
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAt) return entry.principal
            cache.remove(key)
        }

        val response = runCatching {
            client.get(config.userinfoUrl) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrElse { e ->
            log.warn("调用 userinfo 失败: ${e.message}")
            return null
        }

        if (!response.status.isSuccess()) {
            log.debug("userinfo 拒绝该 token: HTTP ${response.status.value}")
            return null
        }

        val claims = runCatching {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        }.getOrNull() ?: return null

        val subject = claims["sub"]?.jsonPrimitive?.content ?: return null
        val groups = (claims["groups"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val principal = VoiceBookPrincipal(
            subject = subject,
            username = claims["preferred_username"]?.jsonPrimitive?.content ?: subject,
            email = claims["email"]?.jsonPrimitive?.content,
            displayName = claims["name"]?.jsonPrimitive?.content,
            groups = groups,
        )

        cache[key] = Entry(principal, System.currentTimeMillis() + ttlMillis)
        return principal
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
