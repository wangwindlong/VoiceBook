package us.wangxy.voicebook.server.upstream

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import us.wangxy.voicebook.server.ServerConfig

/**
 * Miniflux 通道：**管理员代管用户**。
 *
 * 这里踩过一个坑，记下来：Miniflux 的 `AUTH_PROXY_HEADER` 只对 **Web 会话**生效，
 * 那些 `/v1/…` 接口一律走 Basic Auth 或 `X-Auth-Token`（服务端日志会明说
 * "[API] No Basic HTTP Authentication header sent with the request"）。
 * 而 App 不加载 Miniflux 的网页，所以反代注入用户名的路子对 API 行不通。
 *
 * 改成：本服务持一个**管理员 API token**，按需把每个 SSO 用户"投影"成 Miniflux 账号：
 *   1. 已能认证 → 直接用（进程内缓存，不重复探测）
 *   2. 不存在 → `POST /v1/users` 建号
 *   3. `PUT /v1/users/{id}` 把密码设成**由 VB_MINIFLUX_PASSWORD_SECRET 派生的确定值**
 *   4. 之后用 `Basic <username>:<派生密码>` 调 API
 *
 * 好处：每个用户仍是**各自独立的 Miniflux 账号**（订阅、已读、书签互不干扰），
 * 而 BFF 不需要保存任何密码表——密码随时可由密钥重新派生。
 */
class MinifluxUpstream(
    private val config: ServerConfig,
    private val client: HttpClient,
) {
    private val log = LoggerFactory.getLogger(MinifluxUpstream::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** 已经确认可用于认证的用户名，省掉后续请求的探测开销 */
    private val provisioned = ConcurrentHashMap.newKeySet<String>()

    suspend fun proxy(call: ApplicationCall, username: String, pathWithQuery: String) {
        if (!provisioned.contains(username)) {
            ensureUser(username)
        }
        call.relay(
            client = client,
            targetUrl = "${config.minifluxUrl}$pathWithQuery",
            injectHeaders = mapOf(HttpHeaders.Authorization to basicAuth(username)),
        )
    }

    // ---------- 用户投影 ----------

    private suspend fun ensureUser(username: String) {
        // 先探一次：如果凭据已经能用，说明以前配好了，不必再动
        val probe = client.get("${config.minifluxUrl}/v1/me") {
            header(HttpHeaders.Authorization, basicAuth(username))
        }
        if (probe.status.isSuccess()) {
            provisioned.add(username)
            return
        }

        val existingId = findUserId(username)
        val userId = existingId ?: createUser(username)
        setPassword(userId, username, derivedPassword(username))
        provisioned.add(username)
        log.info("Miniflux 用户已就绪: username={} id={} (created={})", username, userId, existingId == null)
    }

    /** 管理员视角列出全部用户，按 username 精确匹配 */
    private suspend fun findUserId(username: String): Long? {
        val response = client.get("${config.minifluxUrl}/v1/users") {
            header("X-Auth-Token", config.minifluxAdminToken)
        }
        if (!response.status.isSuccess()) {
            error("读取 Miniflux 用户列表失败: HTTP ${response.status.value}")
        }
        val users = json.parseToJsonElement(response.bodyAsText()) as? JsonArray ?: return null
        return users.firstOrNull { it.jsonObject["username"]?.jsonPrimitive?.contentOrNull == username }
            ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    private suspend fun createUser(username: String): Long {
        val payload = buildJsonObject {
            put("username", username)
            put("password", derivedPassword(username))
            put("is_admin", false)
        }
        val response = client.post("${config.minifluxUrl}/v1/users") {
            header("X-Auth-Token", config.minifluxAdminToken)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (!response.status.isSuccess()) {
            error("在 Miniflux 建号失败: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
        }
        val created = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return created["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: error("Miniflux 建号成功但没返回 id")
    }

    /**
     * 设置密码。注意 Miniflux 的 `PUT /v1/users/{id}` 把 `username` 当**必填**字段
     * （漏了会回 "The username is mandatory."），所以这里原样带上；
     * 不传 `is_admin`，免得把已有的管理员降权。
     */
    private suspend fun setPassword(userId: Long, username: String, password: String) {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
        }
        val response = client.put("${config.minifluxUrl}/v1/users/$userId") {
            header("X-Auth-Token", config.minifluxAdminToken)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (!response.status.isSuccess()) {
            error("设置 Miniflux 用户密码失败: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
        }
    }

    // ---------- 密码派生 ----------

    private fun basicAuth(username: String): String {
        val raw = "$username:${derivedPassword(username)}".toByteArray()
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    /**
     * HMAC-SHA256(secret, username) 取前 32 位 hex。
     * 确定性：同一密钥下永远得到同一个密码，因此无需密码库表。
     */
    private fun derivedPassword(username: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.minifluxPasswordSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(username.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
