package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.rss.RssPostModel

/** 宽屏右栏预览。 */
@Composable
internal fun PostPreview(post: RssPostModel, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(post.title, style = MaterialTheme.typography.titleLarge)
        Text(
            "${post.feedTitle} · ${relativeTime(post.publishedAt)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(post.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Button(onClick = onOpen) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("阅读全文")
        }
    }
}
