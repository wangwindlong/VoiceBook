package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.rss.RssPostsFilter

@Composable
internal fun RssFeedDrawer(
    filter: RssPostsFilter,
    selectedFeedId: String?,
    feeds: List<FeedWithUnread>,
    onFilter: (RssPostsFilter) -> Unit,
    onSelectFeed: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text("订阅源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        FilterChip(
            selected = filter == RssPostsFilter.Unread,
            onClick = { onFilter(RssPostsFilter.Unread) },
            label = { Text("未读") },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        FilterChip(
            selected = filter == RssPostsFilter.All,
            onClick = { onFilter(RssPostsFilter.All) },
            label = { Text("全部") },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (feeds.isEmpty()) {
            Text(
                "还没有订阅源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn {
            items(feeds, key = { it.feedId }) { feed ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFeed(feed.feedId) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        feed.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedFeedId == feed.feedId) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (feed.unread > 0) {
                        Text(
                            "${feed.unread}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
