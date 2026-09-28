package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.ui.widget.CenteredProgress

@Composable
internal fun PostList(
    posts: LazyPagingItems<RssPostModel>,
    showFeaturedImages: Boolean,
    onOpen: (RssPostModel) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            count = posts.itemCount,
            key = posts.itemKey { it.id },
        ) { index ->
            val post = posts[index] ?: return@items
            PostCard(
                post = post,
                showImage = showFeaturedImages || post.imageUrl != null,
                onClick = { onOpen(post) },
            )
        }
        if (posts.loadState.append is LoadState.Loading) {
            item {
                CenteredProgress(Modifier.fillMaxWidth().padding(12.dp), indicatorSize = 24.dp)
            }
        }
    }
}

/** 圆角卡片：来源·时间 / 标题（未读加粗）/ 摘要 / 尾部缩略图。 */
@Composable
internal fun PostCard(post: RssPostModel, showImage: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${post.feedTitle} · ${relativeTime(post.publishedAt)}" + if (post.hasAudio) "  🔊" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (post.read) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (post.summary.isNotEmpty()) {
                    Text(
                        post.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showImage && post.imageUrl != null) {
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(72.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}
