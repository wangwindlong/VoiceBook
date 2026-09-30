package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Share
import coil3.compose.AsyncImage
import us.wangxy.voicebook.audio.AudioPlayer
import us.wangxy.voicebook.audio.AudioTrack
import us.wangxy.voicebook.rss.RssPostModel

@Composable
internal fun ArticleContent(
    post: RssPostModel?,
    starred: Boolean,
    audioState: us.wangxy.voicebook.audio.AudioState,
    audioPlayer: AudioPlayer,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    likes: Long, liked: Boolean, reacting: Boolean, commentCount: Int,
    notice: String?, related: List<RssPostModel>, onOpenRelated: (String) -> Unit,
    onLike: () -> Unit, onComments: () -> Unit, onShare: () -> Unit,
) {
    LaunchedEffect(post?.seedColor) { onSeedColorChange(post?.seedColor) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(post?.feedTitle ?: "资讯", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, "分享") }
            IconButton(onClick = onToggleStar) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "星标",
                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val current = post
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current.imageUrl != null) {
                AsyncImage(model = current.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${current.feedTitle} · ${relativeTime(current.publishedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (current.hasAudio) {
                InlineAudioBar(
                    title = current.title,
                    source = current.feedTitle,
                    player = audioPlayer,
                    state = audioState,
                    onStart = {
                        audioPlayer.load(
                            AudioTrack(
                                url = current.audioUrl.orEmpty(),
                                title = current.title,
                                source = current.feedTitle,
                                imageUrl = current.imageUrl,
                                postId = current.id,
                            ),
                            current.audioPositionMs,
                        )
                    },
                )
            }
            ArticleBlocks(html = current.contentHtml.ifBlank { current.summary })
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLike, enabled = !reacting) {
                    Icon(Icons.Default.ThumbUp, "点赞", tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(" $likes")
                }
                TextButton(onClick = onComments) { Text("评论 $commentCount") }
                TextButton(onClick = onShare) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Text(" 分享") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("相关文章", style = MaterialTheme.typography.titleMedium)
            if (related.isEmpty()) Text("暂无相关文章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            related.forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { onOpenRelated(item.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(item.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
