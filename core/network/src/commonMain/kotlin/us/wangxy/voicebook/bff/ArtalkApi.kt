package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CaptchaVerifyRequest
import us.wangxy.voicebook.bff.contract.CommentCreateRequest

/** 发评论的结果 —— Artalk 的反垃圾可能要求先过验证码，这不算失败，是一种正常分支。 */
sealed interface CommentPostResult {
    /** 发表成功；[comment] 是 Artalk 原样透传的评论对象。 */
    data class Posted(val comment: JsonObject) : CommentPostResult

    /**
     * Artalk 要求验证码（同一来源 IP 在 `action_reset` 秒内的操作次数超过 `action_limit`）。
     *
     * 界面流程：把 [imgData]（`data:image/png;base64,…`，可直接解码渲染）显示给用户 →
     * 用户填答案 → [ArtalkApi.verifyCaptcha] → 成功后把**同一个请求**再提交一次。
     * Artalk 的放行状态记在服务端（按 IP 记账），不需要任何令牌/cookie。
     */
    data class CaptchaRequired(val imgData: String?, val message: String) : CommentPostResult
}

/** 提交验证码答案的结果。 */
sealed interface CaptchaVerifyResult {
    /** 答案正确：现在可以重发刚才那条评论（一次验证只放行一次）。 */
    object Verified : CaptchaVerifyResult

    /** 答案错误：[imgData] 是 Artalk 换的**新图**，替换显示并请用户重填即可。 */
    data class Wrong(val imgData: String?, val message: String) : CaptchaVerifyResult
}

/**
 * Artalk 点赞状态：[up] 是总点赞数，[isUp] 是**当前来源 IP** 是否已赞。
 *
 * 实测（2026-09-30，对线上 BFF）响应是**扁平结构** `{"up":N,"down":N,"is_up":bool,"is_down":bool}`，
 * 没有 `data` 外壳 —— Artalk 的 `common.RespData` 会把字段摊平在顶层。解析按扁平来，
 * 同时容忍历史/异常情况下多一层 `data`。
 */
data class VoteState(
    val up: Long,
    val down: Long,
    val isUp: Boolean,
    val isDown: Boolean,
)

/** 点赞/取消点赞的结果 —— 与发评论一样，403 验证码是正常分支而不是异常。 */
sealed interface VoteResult {
    /**
     * 操作完成；[state] 是**操作后的新状态**：`isUp=true` 已点赞、`isUp=false` 已取消，
     * `up` 是新的总数。UI 直接以它为准刷新，不要自己算。
     */
    data class Done(val state: VoteState) : VoteResult

    /** Artalk 反垃圾要求先过验证码（每来源 IP 60 秒内 3 次操作，连续点赞常见）。 */
    data class CaptchaRequired(val imgData: String?, val message: String) : VoteResult
}

/**
 * Artalk comments through the BFF, which exchanges the session for an Artalk token server-side.
 * The app has no direct Artalk login, so comments need a signed-in session: without one every
 * call fails with a 401 [BffApiException].
 *
 * Artalk's own response bodies are passed through untouched and returned as raw JSON, the same
 * "parse only what you need" stance as [us.wangxy.voicebook.rss.MinifluxApi].
 */
class ArtalkApi(
    private val client: HttpClient,
    private val session: BffSession,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [pageKey] must start with `/` (e.g. `/book/42`); [sortBy] is `date_asc`, `date_desc` or `vote`. */
    suspend fun comments(pageKey: String, offset: Int = 0, limit: Int = 20, sortBy: String? = null): JsonObject {
        val token = requireToken()
        val response = bffCall {
            client.get(url(session.baseUrl(), BffRoutes.ARTALK_COMMENTS)) {
                bearerAuth(token)
                parameter("page_key", pageKey)
                parameter("offset", offset)
                parameter("limit", limit)
                sortBy?.let { parameter("sort_by", it) }
            }
        }
        return objectOf(response)
    }

    /**
     * 发表评论（name/email 由 BFF 用登录账号填好）。被反垃圾拦下时**不抛异常**，返回
     * [CommentPostResult.CaptchaRequired] —— 403 的 body 里带着验证码图，调用方必须拿到它。
     */
    suspend fun post(request: CommentCreateRequest): CommentPostResult {
        val token = requireToken()
        // 放过 403：Artalk 用「403 + need_captcha」表达「请过验证码」，body 才是关键信息
        val response = bffCall(acceptNon2xx = { it == HttpStatusCode.Forbidden.value }) {
            client.post(url(session.baseUrl(), BffRoutes.ARTALK_COMMENTS)) {
                bearerAuth(token)
                json(request)
            }
        }
        val payload = objectOf(response)
        if (response.status == HttpStatusCode.Forbidden) {
            if (payload.flag("need_captcha")) {
                return CommentPostResult.CaptchaRequired(
                    imgData = payload.str("img_data"),
                    message = payload.str("msg") ?: "需要输入验证码",
                )
            }
            throw BffApiException(403, payload.str("code") ?: "ARTALK_REJECTED", payload.str("msg") ?: "评论被拒绝")
        }
        return CommentPostResult.Posted(payload)
    }

    /**
     * 单独取一张验证码图（`{img_data}`）。一般**不必**调用：`403` 响应里已经带了图，
     * 而且每次取新图都会作废该 IP 的旧图（含用户正在看的那张）。
     */
    suspend fun captcha(): JsonObject {
        val token = requireToken()
        val response = bffCall {
            client.get(url(session.baseUrl(), BffRoutes.ARTALK_CAPTCHA)) { bearerAuth(token) }
        }
        return objectOf(response)
    }

    /** 提交验证码答案；成功后重发刚才那条评论（见 [CaptchaVerifyResult.Verified]）。 */
    suspend fun verifyCaptcha(value: String): CaptchaVerifyResult {
        val token = requireToken()
        val response = bffCall(acceptNon2xx = { it == HttpStatusCode.Forbidden.value }) {
            client.post(url(session.baseUrl(), BffRoutes.ARTALK_CAPTCHA_VERIFY)) {
                bearerAuth(token)
                json(CaptchaVerifyRequest(value))
            }
        }
        if (response.status == HttpStatusCode.Forbidden) {
            val payload = objectOf(response)
            return CaptchaVerifyResult.Wrong(
                imgData = payload.str("img_data"),
                message = payload.str("msg") ?: "验证码不正确",
            )
        }
        return CaptchaVerifyResult.Verified
    }

    /**
     * 查询单条评论的点赞状态。不受反垃圾限制，可以随评论列表并发拉取；
     * 注意「我是否已赞」记在**来源 IP** 上（Artalk 设计），同 WiFi 下多设备共享状态。
     */
    suspend fun voteStatus(commentId: Long): VoteState {
        val token = requireToken()
        val response = bffCall {
            client.get(url(session.baseUrl(), BffRoutes.artalkVoteStatus(commentId))) { bearerAuth(token) }
        }
        return voteStateOf(objectOf(response))
    }

    /**
     * 点赞/取消点赞（Artalk 原生开关语义：同选项再投一次即取消）。请求体 `{}`，身份由 BFF 补。
     * 返回**操作后的新状态**（见 [VoteResult.Done]）。被反垃圾拦下时返回
     * [VoteResult.CaptchaRequired] —— 流程与发评论相同：显示图 → [verifyCaptcha] →
     * 成功后把**同一个点赞请求原样重发一次**。
     */
    suspend fun voteUp(commentId: Long): VoteResult {
        val token = requireToken()
        // 放过 403：Artalk 用「403 + need_captcha」表达「请过验证码」，body 才是关键信息
        val response = bffCall(acceptNon2xx = { it == HttpStatusCode.Forbidden.value }) {
            client.post(url(session.baseUrl(), BffRoutes.artalkVoteCommentUp(commentId))) {
                bearerAuth(token)
                json(JsonObject(emptyMap()))
            }
        }
        val payload = objectOf(response)
        if (response.status == HttpStatusCode.Forbidden) {
            if (payload.flag("need_captcha")) {
                return VoteResult.CaptchaRequired(
                    imgData = payload.str("img_data"),
                    message = payload.str("msg") ?: "需要输入验证码",
                )
            }
            throw BffApiException(403, payload.str("code") ?: "ARTALK_REJECTED", payload.str("msg") ?: "点赞被拒绝")
        }
        return VoteResult.Done(voteStateOf(payload))
    }

    /**
     * 解析点赞状态。**线上实测是扁平的** `{"up":N,"down":N,"is_up":bool,"is_down":bool}`；
     * 万一响应多包了一层 `data` 也能解析（`?: payload` 兜底）。
     */
    private fun voteStateOf(payload: JsonObject): VoteState {
        val data = payload["data"] as? JsonObject ?: payload
        return VoteState(
            up = data.long("up") ?: 0L,
            down = data.long("down") ?: 0L,
            isUp = data.flag("is_up"),
            isDown = data.flag("is_down"),
        )
    }

    private suspend fun objectOf(response: HttpResponse): JsonObject =
        json.parseToJsonElement(response.bodyAsText()).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private suspend fun requireToken(): String =
        session.accessToken() ?: throw BffApiException(401, "NOT_SIGNED_IN", "评论需要先在「我的」登录统一账号")
}
