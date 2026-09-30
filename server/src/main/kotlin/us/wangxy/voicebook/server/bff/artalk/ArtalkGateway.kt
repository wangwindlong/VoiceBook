package us.wangxy.voicebook.server.bff.artalk

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.booleanOrNull
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
 *
 * 每个上游调用都带一个**由账号推导的稳定地址**（见 [artalkIp] / [FORWARDED_FOR]）：Artalk 把
 * 反垃圾计数、验证码状态、点赞的「我赞过没有」判定/去重/开关全部**按来源 IP** 记账，而透传真实
 * 客户端 IP 在局域网里等于所有设备共用一个地址。改成按账号给地址，Artalk 那套 IP 逻辑才等效于
 * **按账号**：同一账号换设备换网络都只算一票，不同账号互不影响。
 */
class ArtalkGateway(
    private val http: HttpClient,
    private val config: ArtalkConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ArtalkToken(val token: String, val expiresAtSeconds: Long?)

    /**
     * 换票结果。Artalk 的反垃圾**同样拦 `/sso/exchange`**（它也是那 8 个受保护 POST 之一），
     * 这时它返回 `403 + {need_captcha, img_data}` —— 那是「先过验证码」的正常分支，必须原样
     * 交给客户端；否则客户端只会看到一个误导性的 502，而且根本拿不到验证码图。
     */
    sealed interface ExchangeResult {
        data class Issued(val token: ArtalkToken) : ExchangeResult

        /** 被反垃圾要求验证码：[upstream] 是 Artalk 的 403（含 `img_data`），原样透出即可。 */
        data class CaptchaRequired(val upstream: UpstreamResponse) : ExchangeResult
    }

    private val tokens = ConcurrentHashMap<String, ArtalkToken>()

    suspend fun tokenFor(principal: BffPrincipal, forceRefresh: Boolean = false): ExchangeResult {
        if (!forceRefresh) {
            tokens[principal.username]
                ?.takeIf { t -> t.expiresAtSeconds == null || t.expiresAtSeconds * 1000 - REFRESH_MARGIN_MS > clock() }
                ?.let { return ExchangeResult.Issued(it) }
        }
        val response = send {
            http.post(config.url + "/api/v2/sso/exchange") {
                header(FORWARDED_FOR, artalkIp(principal))
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("token", principal.accessToken) }.toString())
            }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsBytes()
            if (response.status == HttpStatusCode.Forbidden && isCaptchaChallenge(body)) {
                return ExchangeResult.CaptchaRequired(UpstreamResponse(response.status, response.contentType(), body))
            }
            throw BffException.upstream("artalk", "Artalk SSO 换票失败 HTTP ${response.status.value}: ${String(body).take(200)}")
        }
        val jwt = (BffJson.parseToJsonElement(response.bodyAsText()).jsonObject["token"] as? JsonPrimitive)?.contentOrNull
            ?: throw BffException.upstream("artalk", "Artalk SSO 响应缺少 token")
        return ExchangeResult.Issued(ArtalkToken(jwt, jwtExpiry(jwt)).also { tokens[principal.username] = it })
    }

    /** body 是不是 Artalk 的「需要验证码」挑战（`{"need_captcha": true, "img_data": ...}`）。 */
    private fun isCaptchaChallenge(body: ByteArray): Boolean = runCatching {
        val json = BffJson.parseToJsonElement(String(body)).jsonObject
        (json["need_captcha"] as? JsonPrimitive)?.booleanOrNull == true
    }.getOrDefault(false)

    fun forget(username: String) {
        tokens.remove(username)
    }

    suspend fun listComments(principal: BffPrincipal, pageKey: String, limit: Int, offset: Int, sortBy: String?): UpstreamResponse {
        val response = send {
            http.get(config.url + "/api/v2/comments") {
                header(FORWARDED_FOR, artalkIp(principal))
                parameter("page_key", pageKey)
                parameter("site_name", config.siteName)
                parameter("limit", limit)
                parameter("offset", offset)
                if (sortBy != null) parameter("sort_by", sortBy)
            }
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    /**
     * 取一张新的图片验证码（Artalk `GET /api/v2/captcha` → `{img_data}`）。
     *
     * Artalk 把验证码与操作计数**只按来源 IP** 记账（`atk_captcha:<ip>`，5 分钟过期），
     * 且取新图会**作废同一地址的旧图** —— 所以取图、提交答案（[verifyCaptcha]）与随后重发的
     * 发评论必须解析成**同一个地址**，否则「验证了也不生效」（这里靠 [artalkIp] 的按账号映射保证）。
     */
    suspend fun captcha(principal: BffPrincipal): UpstreamResponse {
        val response = send {
            http.get(config.url + "/api/v2/captcha") {
                header(FORWARDED_FOR, artalkIp(principal))
            }
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    /**
     * 提交验证码答案（Artalk `POST /api/v2/captcha/verify`）。答对返回 200 `{msg:"Success"}`；
     * 答错返回 403 `{msg, img_data}`（附一张**新图**，并使该 IP 的计数再 +1）。
     * 答对后把原发评论请求重发一次即可通过 —— 不需要任何票据，放行状态记在服务端。
     */
    suspend fun verifyCaptcha(principal: BffPrincipal, value: String): UpstreamResponse {
        val response = send {
            http.post(config.url + "/api/v2/captcha/verify") {
                header(FORWARDED_FOR, artalkIp(principal))
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("value", value) }.toString())
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
                header(FORWARDED_FOR, artalkIp(principal))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        val first = tokenFor(principal)
        // 换票当场就被验证码拦下：把那个 403 + 图直接交给客户端（App 弹验证码，答对后重发即可）
        if (first is ExchangeResult.CaptchaRequired) return first.upstream

        var response = post((first as ExchangeResult.Issued).token)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = tokenFor(principal, forceRefresh = true)
            if (refreshed is ExchangeResult.CaptchaRequired) return refreshed.upstream
            response = post((refreshed as ExchangeResult.Issued).token)
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    /**
     * 某条评论的点赞状态（Artalk `GET /api/v2/votes/comment/{id}`）。

     * Artalk 的「我赞过没有」**按来源 IP** 记账（`votes` 表：`type` + `target_id` + `ip`）——
     * 这里传的是 [artalkIp] 推导出的**按账号稳定地址**，于是判定等效于按账号：同一账号换设备也算
     * 已赞、不同账号互不影响（透传真实 IP 会让同 WiFi 的人互相顶掉）。
     * 这个 GET **不受反垃圾限制**（Artalk 只把 LimiterGuard 套在写端点上），可随列表并发拉。

     * 返回 `{"data":{"up":N,"down":N,"is_up":bool,"is_down":bool}}`。
     */
    suspend fun voteStatus(principal: BffPrincipal, commentId: Long): UpstreamResponse {
        val response = send {
            http.get(config.url + "/api/v2/votes/comment/$commentId") {
                header(FORWARDED_FOR, artalkIp(principal))
            }
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    /**
     * 给评论点赞 / 取消点赞（Artalk `POST /api/v2/votes/comment/{id}/up`）。

     * Artalk 原生就是**开关**语义：已赞过再投 `up` 即取消（`updateVote` 先删旧票，
     * 若新选择与旧选择相同则不再写票），响应 `data.is_up` 就是操作后的状态、
     * `data.up` 是新的总数 —— 客户端不需要自己推算。

     * body 里带 name/email：Artalk 用它 `FindCreateUser` 把票挂到真实用户名下（与发评论一致）。

     * **刻意不换票**：Artalk 的 `/votes` 挂在无鉴权中间件的 /api/v2 组上（源码 server.go 实证），
     * 只认 IP + body 里的 name/email（IP 由 [artalkIp] 按账号映射）。省掉一次 `/sso/exchange`
     * 就少消耗一点那个很小的反垃圾额度（默认每账号 60 秒 3 次操作），对「连点几个赞」的体验差别很大。
     */
    suspend fun voteUp(principal: BffPrincipal, commentId: Long): UpstreamResponse {
        val body = buildJsonObject {
            put("name", principal.user.displayName ?: principal.username)
            put("email", principal.user.email ?: "${principal.username}@users.voicebook.invalid")
        }.toString()
        val response = send {
            http.post(config.url + "/api/v2/votes/comment/$commentId/up") {
                header(FORWARDED_FOR, artalkIp(principal))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return UpstreamResponse(response.status, response.contentType(), response.bodyAsBytes())
    }

    /**
     * BFF 向 Artalk 呈现的「账号地址」：由账号名稳定推导出的私网地址（固定 `10.x.y.z`）。
     *
     * **为什么不是真实 IP**：Artalk 把点赞的存在性判定 / 去重 / 开关（`WHERE ... AND ip = ?`）和
     * 验证码计数（`action-count:<ip>`）都只按来源 IP 记账（上游 master 与 v2.10.0 一致）。透传真实
     * 客户端 IP 会带来两个错误：① 局域网经网关 NAT 后所有设备收敛成同一个地址 → 不同账号互相顶掉
     * 点赞、共用一份反垃圾额度（一人操作几次，其他人就被弹验证码）；② 同一账号换网络（WiFi↔4G）
     * 又变成两个地址 → **重复计票**。按账号给地址后，Artalk 的 IP 语义等效于「按账号」。
     *
     * 取值为 `username.hashCode()` 的 24 位 → `10.a.b.c`（1600 万种取值，家用规模下撞号概率可忽略）。
     * 代价：Artalk 的 `comments.ip` / `votes.ip` 记的是这个合成地址；真实客户端 IP 仍在 BFF 侧可见
     * （[clientIp] 用于本服务自己的限频），溯源看 BFF 日志。要恢复真实 IP，把 [artalkIp] 调用换回
     * `clientIp` 即可 —— 但会退回上面那两个错误。
     */
    private fun artalkIp(principal: BffPrincipal): String {
        val h = principal.username.hashCode()
        return "10.${(h shr 16) and 0xFF}.${(h shr 8) and 0xFF}.${h and 0xFF}"
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffException.upstream("artalk", "无法连接 Artalk", e)
    }

    private companion object {
        /**
         * Artalk 侧 artalk.yml 配的是 `http.proxy_header: "X-Forwarded-For"` —— 它只认这个头、
         * 不看 TCP 对端，而它据此记账的三件事恰好都是身份语义：评论/票的来源、点赞的「我赞过没有」
         * 与去重、验证码与反垃圾计数。BFF 是所有 App 用户的单一出口，所以这里传什么，就决定了
         * Artalk 眼里的「谁是谁」。
         *
         * 传**按账号推导的稳定地址**（[artalkIp]，不是真实客户端 IP）—— 见那个函数的完整理由。
         * 值必须是**单个 IP**（Artalk 把该头的值原样存进 `comments.ip`，不做解析/取首段）。
         */
        const val FORWARDED_FOR = "X-Forwarded-For"

        const val REFRESH_MARGIN_MS = 60_000L

        fun jwtExpiry(jwt: String): Long? = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(jwt.split('.')[1]))
            (BffJson.parseToJsonElement(payload).jsonObject["exp"] as? JsonPrimitive)?.longOrNull
        }.getOrNull()
    }
}
