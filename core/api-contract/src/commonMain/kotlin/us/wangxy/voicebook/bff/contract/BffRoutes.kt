package us.wangxy.voicebook.bff.contract

/** Paths exposed by the BFF. The app must not talk to Miniflux / Artalk / calibre directly. */
object BffRoutes {
    const val HEALTH = "/healthz"

    const val AUTH_CONFIG = "/api/auth/config"
    const val AUTH_LOGIN = "/api/auth/login"
    const val AUTH_REFRESH = "/api/auth/refresh"
    const val AUTH_LOGOUT = "/api/auth/logout"
    const val AUTH_REGISTER = "/api/auth/register"
    const val AUTH_PASSWORD = "/api/auth/password"
    const val AUTH_ME = "/api/auth/me"
    const val AUTH_TOKEN_EXCHANGE = "/api/auth/token/exchange"

    /** Prefix of the Miniflux pass-through; `/api/miniflux/entries` maps to Miniflux `/v1/entries`. */
    const val MINIFLUX = "/api/miniflux"

    const val CALIBRE_BOOKS = "/api/calibre/books"
    const val CALIBRE_PROGRESS = "/api/calibre/progress"
    const val CALIBRE_HISTORY = "/api/calibre/history"
    const val CALIBRE_TAGS = "/api/calibre/tags"
    const val EVENTS = "/api/events"
    const val FOR_YOU = "/api/for-you"
    const val PROFILE = "/api/profile"
    fun calibreHistory(limit: Int = 50) = "$CALIBRE_HISTORY?limit=$limit"
    const val CALIBRE_ACTIVATE = "/api/calibre/activate"
    const val BOOK_UPLOAD = "/api/calibre/uploads"
    const val ARTICLE_REACTIONS = "/api/articles/reactions"
    const val FEED_CATEGORIES = "/api/feeds/categories"
    fun articleReaction(key: String) = "$ARTICLE_REACTIONS/$key"

    const val ARTALK_COMMENTS = "/api/artalk/comments"

    /**
     * Artalk 反垃圾验证码：取图与提交答案。
     *
     * 发评论被反垃圾拦下时返回 403 `{need_captcha: true, img_data}`，App 用这张图向用户要答案，
     * 提交成功后**把原发评论请求原样重发一次**即可（Artalk 的放行状态记在服务端、按 IP 记账，
     * 不需要任何票据）。
     */
    const val ARTALK_CAPTCHA = "/api/artalk/captcha"
    const val ARTALK_CAPTCHA_VERIFY = "/api/artalk/captcha/verify"

    /**
     * 评论点赞。Artalk 原生就是「开关」语义：对同一条评论用同一个选项再投一次即**取消点赞**，
     * 客户端不需要传「取消」标志。
     *
     * - `GET artalkVoteStatus(id)` → `{"up":N,"down":N,"is_up":bool,"is_down":bool}`
     *   （**扁平结构**，实测无 `data` 外壳；`is_up` 是**当前来源 IP** 是否点过赞。
     *   这个 GET 不受反垃圾限制，可放心随评论列表并发拉取）
     * - `POST artalkVoteCommentUp(id)` → 同形状，返回**操作后的新状态**：`is_up=true` 已点赞、
     *   `is_up=false` 已取消，同时 `up` 是新的总数
     *
     * 两个端点都要求登录（Bearer）。（POST 会被 Artalk 反垃圾守：被拦时与发评论一样返回
     * 403 `{need_captcha:true, img_data}`。）
     */
    const val ARTALK_VOTES_COMMENT = "/api/artalk/votes/comment"

    fun artalkVoteStatus(commentId: Long) = "$ARTALK_VOTES_COMMENT/$commentId"
    fun artalkVoteCommentUp(commentId: Long) = "$ARTALK_VOTES_COMMENT/$commentId/up"

    fun calibreBook(id: Long) = "$CALIBRE_BOOKS/$id"
    fun calibreCover(id: Long) = "$CALIBRE_BOOKS/$id/cover"
    fun calibreFile(id: Long, format: String) = "$CALIBRE_BOOKS/$id/file/${format.uppercase()}"
    fun calibreProgress(bookId: Long) = "$CALIBRE_PROGRESS/$bookId"
    fun minifluxMarkRead(entryId: Long) = "$MINIFLUX/entries/$entryId/read"
}

object BffComponents {
    const val MINIFLUX = "miniflux"
    const val CALIBRE = "calibre"
    const val ARTALK = "artalk"
}
