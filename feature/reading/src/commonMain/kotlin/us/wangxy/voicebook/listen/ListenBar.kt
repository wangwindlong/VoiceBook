package us.wangxy.voicebook.listen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Transport bar shown at the bottom of the reader while its book is being read aloud. */
@Composable
fun ListenBar(state: ListenState, controller: ListenController, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                statusLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = controller::previousChapter) { Text("上章") }
                IconButton(onClick = controller::previousSentence) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一句")
                }
                PlayPauseButton(state, controller)
                IconButton(onClick = controller::nextSentence) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一句")
                }
                TextButton(onClick = controller::nextChapter) { Text("下章") }
                TextButton(onClick = controller::cycleSpeed) { Text(speedLabel(state.speed)) }
                if (controller.supportsVoiceCommands) {
                    TextButton(onClick = controller::listenForCommand, enabled = !state.awaitingCommand) { Text("指令") }
                }
                IconButton(onClick = controller::stop) { Icon(Icons.Filled.Close, "退出听书") }
            }
        }
    }
}

/** Compact bar for the app shell, so playback stays controllable after leaving the reader. */
@Composable
fun ListenMiniBar(state: ListenState, controller: ListenController, onOpen: () -> Unit) {
    if (!state.active) return
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(state, controller)
            Column(Modifier.weight(1f)) {
                Text(
                    "听书 · ${state.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLine(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = controller::stop) { Icon(Icons.Filled.Close, "退出听书") }
        }
    }
}

@Composable
private fun PlayPauseButton(state: ListenState, controller: ListenController) {
    when {
        state.status == ListenStatus.Preparing -> IconButton(onClick = controller::pause) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        state.playing -> TextButton(onClick = controller::pause) { Text("暂停") }
        else -> IconButton(onClick = controller::toggle) { Icon(Icons.Filled.PlayArrow, "播放") }
    }
}

private fun statusLine(state: ListenState): String = when {
    state.awaitingCommand -> "正在听指令…"
    state.message != null -> state.message
    state.status == ListenStatus.Preparing -> "正在准备语音…"
    else -> state.sentence?.text ?: state.chapterTitle
}

private fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100 + 0.5f).toInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return if (frac == 0) "$whole.0x" else if (frac % 10 == 0) "$whole.${frac / 10}x" else "$whole.${frac}x"
}
