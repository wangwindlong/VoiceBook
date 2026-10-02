package us.wangxy.voicebook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.data.ReaderSessionRepository

/** Foreground reconnect retries run independently of the visible reader. */
@Composable
internal fun ReadingSyncHost() {
    val sessions = koinInject<ReaderSessionRepository>()
    val books = koinInject<BookRepository>()
    val account by sessions.owners.collectAsStateWithLifecycle()
    val serverVersion by books.serverVersion.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account, serverVersion, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sessions.refreshHistory()
            var retries = 0
            while (true) {
                delay(15_000)
                retries++
                if (retries % 2 == 0) sessions.refreshHistory() else sessions.retryPendingProgress()
            }
        }
    }
}
