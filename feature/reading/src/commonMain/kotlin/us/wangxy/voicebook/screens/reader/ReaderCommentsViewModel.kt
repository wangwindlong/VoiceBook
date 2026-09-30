package us.wangxy.voicebook.screens.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import us.wangxy.voicebook.bff.ArtalkApi
import us.wangxy.voicebook.bff.BffApiException
import us.wangxy.voicebook.bff.CaptchaVerifyResult
import us.wangxy.voicebook.bff.VoteResult

/**
 * 一条评论在 UI 上的完整形态。内容/昵称/头像/时间/点赞数来自 Artalk（真数据）；
 * 进度/时长/笔记来自 [CommentExtrasProvider]（当前是假数据，见 [CommentExtras]）。
 */
data class CommentRow(
    val id: Long,
    val nick: String,
    /** Gravatar 头像 URL（由 email 的 MD5 hex 推导）；null 时显示昵称首字母色块。 */
    val avatarUrl: String?,
    val content: String,
    /** Artalk 的 ISO8601 时间字符串，原样保存。 */
    val date: String,
    /** 当前账号是否已赞（BFF 按账号给 Artalk 一个稳定地址，等价于按账号记状态）。 */
    val liked: Boolean,
    val votes: Long,
    val extras: CommentExtras,
    /** 该条正在投票（乐观更新后等待服务端确认），期间禁止重复点击。 */
    val voting: Boolean = false,
)

/** 点赞触发的验证码：显示图 → 用户输入 → 验证成功后 [retry] 把**同一个点赞请求原样重发一次**。 */
data class PendingCaptcha(
    val imgData: String?,
    val message: String,
    val retry: suspend () -> Unit,
)

data class ReaderCommentsUiState(
    val loading: Boolean = false,
    /** 未登录：列表区只显示「登录后可看评论」，不弹异常堆栈。 */
    val signedOut: Boolean = false,
    /** 拉取列表失败的提示（点赞失败走 [notice]，不覆盖已有列表）。 */
    val error: String? = null,
    /** 该书评论总数（列表响应的 count），驱动顶栏角标；0 时不显示角标。 */
    val total: Int = 0,
    val comments: List<CommentRow> = emptyList(),
    val captcha: PendingCaptcha? = null,
    /** 一次性提示（点赞失败/限频等），几秒后自动消失。 */
    val notice: String? = null,
)

/**
 * 读书页评论列表的状态容器：装载某本书的全部评论、逐条补齐点赞状态、
 * 处理点赞/取消点赞（乐观更新 + 服务端确认 + 失败回滚 + 验证码重发）。
 *
 * 评论页 page_key 约定为 `/calibre/book/{bookId}`，列表与点赞共用同一个 key。
 */
class ReaderCommentsViewModel(
    private val artalk: ArtalkApi,
    private val extrasProvider: CommentExtrasProvider,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(ReaderCommentsUiState())
    val state: StateFlow<ReaderCommentsUiState> = stateFlow.asStateFlow()

    private var pageKey = ""
    private var loadJob: Job? = null

    /** 打开列表（或下拉刷新）时调用：重新拉一遍，顺带刷新角标。 */
    fun open(key: String) {
        pageKey = key
        refresh()
    }

    fun refresh() {
        val key = pageKey
        if (key.isBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            stateFlow.update { it.copy(loading = true, signedOut = false, error = null) }
            try {
                // 最新评论在前，符合评论区习惯
                val payload = artalk.comments(key, offset = 0, limit = ListLimit, sortBy = "date_desc")
                val (total, rows) = parseComments(payload, key)
                stateFlow.update { it.copy(loading = false, total = total, comments = rows) }
                fetchVoteStates(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BffApiException) {
                if (e.isUnauthorized) {
                    // 未登录/登录过期：给一句人话，不崩、不显示堆栈
                    stateFlow.update {
                        it.copy(loading = false, signedOut = true, comments = emptyList(), total = 0)
                    }
                } else {
                    stateFlow.update { it.copy(loading = false, error = e.message) }
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(loading = false, error = e.message ?: "评论加载失败") }
            }
        }
    }

    /**
     * 静默刷新顶栏角标：只取 count（limit=1 省流量）。任何失败都吞掉 ——
     * 未登录时角标不显示即可，别打扰阅读。
     */
    fun prefetchTotal(key: String) {
        viewModelScope.launch {
            try {
                val payload = artalk.comments(key, offset = 0, limit = 1, sortBy = "date_desc")
                val total = payload.primitive("count")?.contentOrNull?.toIntOrNull() ?: return@launch
                stateFlow.update { it.copy(total = total) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 角标拿不到就保持隐藏
            }
        }
    }

    /**
     * 点赞/取消点赞：先乐观更新（icon 高亮 + 计数 ±1），以服务端返回的新状态为准
     * （[VoteResult.Done] 的 isUp/up，不自己算），失败回滚并提示。
     */
    fun toggleLike(commentId: Long) {
        val row = stateFlow.value.comments.firstOrNull { it.id == commentId } ?: return
        if (row.voting) return
        applyRow(
            row.copy(
                liked = !row.liked,
                votes = row.votes + if (row.liked) -1 else +1,
                voting = true,
            ),
        )
        viewModelScope.launch {
            try {
                when (val result = artalk.voteUp(commentId)) {
                    is VoteResult.Done -> applyVote(commentId, result)
                    is VoteResult.CaptchaRequired -> onCaptchaRequired(commentId, row, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyRow(row) // 回滚乐观更新
                reportVoteFailure(e)
            }
        }
    }

    /** 提交验证码答案：答对后立刻重发被拦的点赞请求；答错则显示 Artalk 换的新图。 */
    fun submitCaptcha(value: String) {
        val pending = stateFlow.value.captcha ?: return
        viewModelScope.launch {
            try {
                when (val result = artalk.verifyCaptcha(value)) {
                    CaptchaVerifyResult.Verified -> {
                        // 一次验证只放行一次操作：把刚才被拦的点赞原样重发
                        stateFlow.update { it.copy(captcha = null) }
                        pending.retry()
                    }
                    is CaptchaVerifyResult.Wrong -> stateFlow.update {
                        it.copy(captcha = pending.copy(imgData = result.imgData, message = result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice(e.message ?: "验证失败，请稍后再试")
            }
        }
    }

    fun dismissCaptcha() {
        stateFlow.update { it.copy(captcha = null) }
    }

    fun dismissNotice() {
        stateFlow.update { it.copy(notice = null) }
    }

    /** 点赞没发生（被 403 拦下）：先回滚乐观更新，再弹验证码，过了之后原样重发。 */
    private suspend fun onCaptchaRequired(
        commentId: Long,
        before: CommentRow,
        result: VoteResult.CaptchaRequired,
    ) {
        applyRow(before)
        stateFlow.update {
            it.copy(captcha = PendingCaptcha(result.imgData, result.message) { resendVote(commentId) })
        }
    }

    /** 验证码通过后的重发：不再乐观更新，直接等服务端给出的新状态。 */
    private suspend fun resendVote(commentId: Long) {
        try {
            when (val result = artalk.voteUp(commentId)) {
                is VoteResult.Done -> applyVote(commentId, result)
                is VoteResult.CaptchaRequired -> stateFlow.update {
                    // 又被拦（反垃圾额度按账号记账，连续点赞常见）：换图再来，属正常分支
                    it.copy(captcha = PendingCaptcha(result.imgData, result.message) { resendVote(commentId) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportVoteFailure(e)
        }
    }

    /** 逐条补齐点赞状态（is_up 按账号判定）。单条失败保持列表里的 vote_up，不阻塞整页。 */
    private suspend fun fetchVoteStates(rows: List<CommentRow>) {
        if (rows.isEmpty()) return
        val gate = Semaphore(ConcurrentVotes)
        coroutineScope {
            for (row in rows) {
                launch {
                    gate.withPermit {
                        val vote = try {
                            artalk.voteStatus(row.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            return@withPermit
                        }
                        stateFlow.update { s ->
                            s.copy(comments = s.comments.map { r ->
                                // 用户可能已经先点了（乐观更新中），别用旧状态覆盖
                                if (r.id == row.id && !r.voting) r.copy(liked = vote.isUp, votes = vote.up) else r
                            })
                        }
                    }
                }
            }
        }
    }

    private fun applyVote(commentId: Long, done: VoteResult.Done) {
        stateFlow.update { s ->
            s.copy(comments = s.comments.map { row ->
                if (row.id == commentId) {
                    row.copy(liked = done.state.isUp, votes = done.state.up, voting = false)
                } else {
                    row
                }
            })
        }
    }

    private fun applyRow(row: CommentRow) {
        stateFlow.update { s ->
            s.copy(comments = s.comments.map { if (it.id == row.id) row else it })
        }
    }

    private fun reportVoteFailure(e: Exception) {
        showNotice(
            when {
                e is BffApiException && e.status == 429 ->
                    e.retryAfterSeconds?.let { "操作过于频繁，请 $it 秒后再试" }
                        ?: (e.message ?: "操作过于频繁，请稍后再试")
                else -> e.message ?: "点赞失败，请稍后再试"
            },
        )
    }

    private fun showNotice(message: String) {
        stateFlow.update { it.copy(notice = message) }
        viewModelScope.launch {
            delay(NoticeMillis)
            stateFlow.update { if (it.notice == message) it.copy(notice = null) else it }
        }
    }

    /** 解析 Artalk 原样透传的列表响应：`{"comments":[…],"count":<该书总数>,"page":{…}}`。 */
    private fun parseComments(payload: JsonObject, key: String): Pair<Int, List<CommentRow>> {
        val total = payload.primitive("count")?.contentOrNull?.toIntOrNull() ?: 0
        val array = payload["comments"] as? JsonArray ?: JsonArray(emptyList())
        val rows = array.mapNotNull { element ->
            val comment = element as? JsonObject ?: return@mapNotNull null
            val id = comment.primitive("id")?.longOrNull ?: return@mapNotNull null
            CommentRow(
                id = id,
                nick = comment.primitive("nick")?.contentOrNull?.takeIf { it.isNotBlank() } ?: "匿名书友",
                avatarUrl = gravatarUrl(comment.primitive("email_encrypted")?.contentOrNull.orEmpty()),
                content = comment.primitive("content")?.contentOrNull.orEmpty(),
                date = comment.primitive("date")?.contentOrNull.orEmpty(),
                liked = false,
                votes = comment.primitive("vote_up")?.longOrNull ?: 0L,
                extras = extrasProvider.extrasFor(id, key),
            )
        }
        return total to rows
    }

    /**
     * 头像推导：Artalk 的 `email_encrypted` 就是邮箱的 MD5 hex，直接拼 Gravatar；
     * 为空或加载失败时由 UI 显示昵称首字母色块占位。
     */
    private fun gravatarUrl(emailHash: String): String? =
        emailHash.takeIf { it.isNotBlank() }?.let { "https://www.gravatar.com/avatar/$it?d=mp&s=120" }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private companion object {
        /** 一本书的评论一次拉满（50 条）；评论量大了再补翻页。 */
        const val ListLimit = 50
        const val ConcurrentVotes = 8
        const val NoticeMillis = 3_000L
    }
}
