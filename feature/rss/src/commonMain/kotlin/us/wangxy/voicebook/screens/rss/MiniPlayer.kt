package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import us.wangxy.voicebook.ui.widget.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.wangxy.voicebook.audio.AudioPlayer

/** 常驻底部迷你播放条：有活动音轨才显示（Twine NowPlayingBottomBar 的简化版）。 */
@Composable
fun MiniPlayer(player: AudioPlayer, onProgressPersist: () -> Unit = {}) {
    val state by player.state.collectAsStateWithLifecycle()
    val track = state.track ?: return

    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.toggle() }) {
                    Icon(
                        if (state.playing) Icons.Filled.PlayArrow else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    onProgressPersist()
                    player.stop()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { state.positionMs.toFloat() / state.durationMs },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
