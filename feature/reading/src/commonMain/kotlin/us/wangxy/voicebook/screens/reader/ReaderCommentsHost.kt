package us.wangxy.voicebook.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

/**
 * 阅读页评论区的粘合层：把 [ReaderCommentsViewModel] 与固定的 `page_key`、
 * 书名绑定起来，供所有类型的阅读页（EPUB/TXT/PDF…）共用。
 *
 * 页面只负责在自己的顶栏放评论入口并调用 [ReaderCommentsController.open]；
 * [ReaderCommentsSheet] 由宿主页面统一渲染，因此新增阅读类型时不必再写一遍评论逻辑。
 */
@Stable
internal class ReaderCommentsController(
    private val viewModel: ReaderCommentsViewModel,
    private val pageKey: String,
    private val title: String,
) {
    /** 供顶栏角标等读取；UI 侧仍应 `collectAsStateWithLifecycle` 后使用。 */
    val state: StateFlow<ReaderCommentsUiState> = viewModel.state

    fun open() = viewModel.open(pageKey, title)

    fun toggleLike(commentId: Long) = viewModel.toggleLike(commentId)

    fun startReply(commentId: Long, nick: String) = viewModel.startReply(commentId, nick)

    fun cancelReply() = viewModel.cancelReply()

    fun updateDraft(text: String) = viewModel.updateDraft(text)

    fun sendMessage() = viewModel.sendMessage()

    fun refresh() = viewModel.refresh()

    fun submitCaptcha(value: String) = viewModel.submitCaptcha(value)

    fun dismissCaptcha() = viewModel.dismissCaptcha()

    fun dismissNotice() = viewModel.dismissNotice()
}

/** 在当前阅读页创建（并记住）评论区控制器；进页即静默取一次总数给角标用。 */
@Composable
internal fun rememberReaderComments(bookId: Int, title: String): ReaderCommentsController {
    val viewModel = koinViewModel<ReaderCommentsViewModel>()
    val pageKey = remember(bookId) { "/calibre/book/$bookId" }
    LaunchedEffect(pageKey) { viewModel.prefetchTotal(pageKey) }
    return remember(viewModel, pageKey, title) {
        ReaderCommentsController(viewModel, pageKey, title)
    }
}

/** 评论区抽屉；只在需要显示时组合，关闭走 [onDismiss]。 */
@Composable
internal fun ReaderCommentsSheet(
    controller: ReaderCommentsController,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    CommentsSheet(
        state = state,
        onLike = controller::toggleLike,
        onReply = controller::startReply,
        onCancelReply = controller::cancelReply,
        onDraftChange = controller::updateDraft,
        onSend = controller::sendMessage,
        onRetry = controller::refresh,
        onCaptchaSubmit = controller::submitCaptcha,
        onCaptchaDismiss = controller::dismissCaptcha,
        onNoticeDismissed = controller::dismissNotice,
        onDismiss = onDismiss,
    )
}
