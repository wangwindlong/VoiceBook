package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.ui.widget.CenteredProgress

@Composable
internal fun SearchResults(
    results: List<RssPostModel>,
    searching: Boolean,
    onOpen: (RssPostModel) -> Unit,
) {
    when {
        searching -> CenteredProgress(Modifier.fillMaxSize())
        results.isEmpty() -> EmptySearch()
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results, key = { it.id }) { post ->
                PostCard(post = post, showImage = true, onClick = { onOpen(post) })
            }
        }
    }
}

@Composable
private fun EmptySearch() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "没有匹配的文章",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
