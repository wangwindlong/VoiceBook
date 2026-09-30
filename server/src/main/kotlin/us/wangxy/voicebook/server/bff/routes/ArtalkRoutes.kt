package us.wangxy.voicebook.server.bff.routes

import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CaptchaVerifyRequest
import us.wangxy.voicebook.bff.contract.CommentCreateRequest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.artalk.ArtalkGateway

private val SORTS = setOf("date_asc", "date_desc", "vote")

private fun validPageKey(key: String?) = key != null && key.startsWith("/") && key.length <= 512

fun Route.artalkRoutes(gateway: ArtalkGateway) {
    get(BffRoutes.ARTALK_COMMENTS) {
        val pageKey = call.request.queryParameters["page_key"]
        if (!validPageKey(pageKey)) throw BffException.badRequest("page_key 需以 / 开头且不超过 512 字符")
        val sortBy = call.request.queryParameters["sort_by"]?.also {
            if (it !in SORTS) throw BffException.badRequest("sort_by 仅支持 $SORTS")
        }
        call.respondUpstream(
            gateway.listComments(
                // 以「按账号推导的稳定地址」呈现给 Artalk（见 ArtalkGateway.artalkIp 的理由）
                principal = call.bffPrincipal,
                pageKey = pageKey!!,
                limit = call.intParam("limit", 20, 1..100),
                offset = call.intParam("offset", 0, 0..Int.MAX_VALUE),
                sortBy = sortBy,
            ),
        )
    }

    /**
     * 发表评论。BFF 侧另有一道**硬限频**（[COMMENT_LIMIT]）：同一 key（已登录取用户名、未登录
     * 取 IP）在 `commentRateWindowMinutes` 分钟内最多 `commentRateLimit` 条，超限直接 429，
     * 请求根本不落到 Artalk。它与 Artalk 自带的验证码（更轻的门槛，默认 3 次/60 秒）独立计数。
     */
    rateLimit(COMMENT_LIMIT) {
        post(BffRoutes.ARTALK_COMMENTS) {
            val request = call.receive<CommentCreateRequest>()
            if (!validPageKey(request.pageKey)) throw BffException.badRequest("pageKey 需以 / 开头且不超过 512 字符")
            if (request.content.isBlank() || request.content.length > 5000) throw BffException.badRequest("评论内容为空或超过 5000 字")
            call.respondUpstream(gateway.createComment(call.bffPrincipal, request))
        }
    }

    /**
     * 取一张新验证码图（`{img_data}`）。
     *
     * 与发评论解析成**同一个地址**：Artalk 把验证码与操作计数只按来源 IP 记账，
     * 地址换一个就等于「这张图不是你的」（BFF 按账号给地址，见 ArtalkGateway.artalkIp）。
     */
    get(BffRoutes.ARTALK_CAPTCHA) {
        call.respondUpstream(gateway.captcha(call.bffPrincipal))
    }

    /**
     * 评论点赞状态（`{"data":{"up":N,"down":N,"is_up":bool,"is_down":bool}}`）。
     *
     * Artalk 按**来源 IP** 判断「我赞过没有」，BFF 按账号映射成一个稳定地址（见 ArtalkGateway.artalkIp），
     * 于是判定等效于按账号。
     * 该 GET **不受反垃圾限制**（Artalk 只把 LimiterGuard 套在写端点上），可随评论列表并发拉取。
     */
    get("${BffRoutes.ARTALK_VOTES_COMMENT}/{id}") {
        call.respondUpstream(gateway.voteStatus(call.bffPrincipal, call.longPath("id")))
    }

    /**
     * 点赞 / 取消点赞。Artalk 原生就是开关语义：已赞过再投一次同一个 `up` 即取消，
     * 响应里带回**操作后的状态**（`is_up`）与**新总数**（`up`）。
     *
     * 会被 Artalk 反垃圾守：被拦时返回 403 `{need_captcha, img_data}` —— App 走一次验证码
     * 后把本请求**原样重发**即可（与发评论同一条流程）。
     */
    post("${BffRoutes.ARTALK_VOTES_COMMENT}/{id}/up") {
        call.respondUpstream(gateway.voteUp(call.bffPrincipal, call.longPath("id")))
    }

    /**
     * 提交验证码答案。答对 200，答错 403（附新图，App 直接换图重试）。
     * 答对后把原发评论请求**原样重发一次**即可 —— Artalk 的放行状态记在服务端，不给票据。
     */
    post(BffRoutes.ARTALK_CAPTCHA_VERIFY) {
        val request = call.receive<CaptchaVerifyRequest>()
        val value = request.value.trim()
        if (value.isEmpty() || value.length > 16) throw BffException.badRequest("验证码需为 1-16 个字符")
        call.respondUpstream(gateway.verifyCaptcha(call.bffPrincipal, value))
    }
}
