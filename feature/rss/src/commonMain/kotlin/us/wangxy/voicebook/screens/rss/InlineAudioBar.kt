package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.audio.AudioPlayer
import us.wangxy.voicebook.audio.AudioState

/** 内嵌音频条：进度条 + 播放/暂停 + 续播。 */
@Composable
internal fun InlineAudioBar(
    title: String,
    source: String,
    player: AudioPlayer,
    state: AudioState,
    onStart: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = {
                    if (state.track == null) onStart() else player.toggle()
                }) {
                    Icon(
                        if (state.playing) Icons.Filled.PlayArrow else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.durationMs > 0) {
                Slider(
                    value = state.positionMs.toFloat() / state.durationMs,
                    onValueChange = { player.seekTo(it) },
                )
            }
        }
    }
}
