package us.wangxy.voicebook.ui.widget

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** Shared reader notices: optional action, manual dismissal, and at most 20 seconds on screen. */
class ReadingNoticeHostState internal constructor(private val scope: CoroutineScope) {
    internal val snackbar = SnackbarHostState()
    private data class NoticeKey(val message: String, val actionLabel: String?)
    private class PendingNotice(val visuals: ReadingNoticeVisuals) {
        val result = CompletableDeferred<SnackbarResult>()
    }
    private val pending = mutableMapOf<NoticeKey, PendingNotice>()

    /** Identical visible or queued notices share one result and refresh their timeout.
     * Cancelling a caller (for example, a restarted effect) does not remove the notice.
     * The remembered host scope owns its lifetime and cancels it when the reader leaves.
     */
    suspend fun show(
        message: String,
        actionLabel: String? = null,
        durationMillis: Long = 20_000,
    ): SnackbarResult {
        val key = NoticeKey(message, actionLabel)
        val duration = durationMillis.coerceIn(1, 20_000)
        val existing = pending[key]
        if (existing != null) {
            existing.visuals.refresh(duration)
            return existing.result.await()
        }
        val notice = PendingNotice(ReadingNoticeVisuals(message, actionLabel, duration))
        pending[key] = notice
        scope.launch {
            try {
                notice.result.complete(snackbar.showSnackbar(notice.visuals))
            } finally {
                pending.remove(key)
                notice.result.cancel()
            }
        }
        return notice.result.await()
    }

    fun dismiss() {
        snackbar.currentSnackbarData?.dismiss()
    }
}

private class ReadingNoticeVisuals(
    override val message: String,
    override val actionLabel: String?,
    durationMillis: Long,
) : SnackbarVisuals {
    var durationMillis by mutableLongStateOf(durationMillis)
        private set
    var revision by mutableLongStateOf(0)
        private set

    fun refresh(durationMillis: Long) {
        this.durationMillis = durationMillis
        revision++
    }

    override val withDismissAction = true
    // The host owns the timeout so accessibility settings cannot extend it past the cap.
    override val duration = SnackbarDuration.Indefinite
}

@Composable
fun rememberReadingNoticeHostState(): ReadingNoticeHostState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ReadingNoticeHostState(scope) }
}

/** Place at the bottom of a reader; reminders share the same queue and dismissal behavior. */
@Composable
fun ReadingNoticeHost(
    state: ReadingNoticeHostState,
    modifier: Modifier = Modifier,
) {
    val notice = state.snackbar.currentSnackbarData
    val visuals = notice?.visuals as? ReadingNoticeVisuals
    LaunchedEffect(notice, visuals?.revision) {
        if (notice != null) {
            delay(visuals?.durationMillis ?: 20_000)
            notice.dismiss()
        }
    }
    SnackbarHost(hostState = state.snackbar, modifier = modifier) { data ->
        Snackbar(snackbarData = data)
    }
}
