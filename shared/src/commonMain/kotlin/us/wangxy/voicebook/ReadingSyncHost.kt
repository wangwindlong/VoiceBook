package us.wangxy.voicebook

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.data.ReaderSessionRepository

/** Foreground reconnect retries and a shared conflict dialog for home, shelf and reader. */
@Composable
internal fun ReadingSyncHost() {
    val sessions = koinInject<ReaderSessionRepository>()
    val books = koinInject<BookRepository>()
    val account by sessions.owners.collectAsStateWithLifecycle()
    val serverVersion by books.serverVersion.collectAsStateWithLifecycle()
    val conflicts by sessions.progressConflicts.collectAsStateWithLifecycle()
    val error by sessions.progressSyncError.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf<us.wangxy.voicebook.reader.api.CalibreServer?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account, serverVersion, lifecycle) {
        server = sessions.server()
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
    val conflict = conflicts.firstOrNull { it.account == account.orEmpty() && it.server == server } ?: return
    var busy by remember(conflict) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun choose(useCloud: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try { sessions.resolveConflict(conflict, useCloud) }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("选择阅读进度") },
        text = {
            val title = conflict.local.title.ifBlank { "这本书" }
            fun position(entry: us.wangxy.voicebook.reader.store.HistoryEntry): String =
                if (entry.format == "PDF") "第 ${entry.spineIndex + 1} 页" else
                    "第 ${entry.spineIndex + 1} 章，约第 ${entry.charOffset.coerceAtLeast(0) + 1} 字"
            Text("《$title》的本机与云端进度不同。\n\n本机：已读 ${conflict.local.progress}% · ${position(conflict.local)}\n云端：已读 ${conflict.cloud.progress}% · ${position(conflict.cloud)}\n\n使用云端会恢复云端位置；保留本机会将本机位置同步到云端。" +
                (error?.let { "\n\n$it" } ?: ""))
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { choose(true) }) { Text("使用云端") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { choose(false) }) { Text("保留本机") } },
    )
}
