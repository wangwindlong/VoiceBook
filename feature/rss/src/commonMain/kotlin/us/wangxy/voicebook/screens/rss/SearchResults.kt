package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.ui.widget.CenteredProgress

@Composable
internal fun SearchResults(
    results: LazyPagingItems<RssPostModel>,
    onOpen: (RssPostModel) -> Unit,
) {
    when {
        results.itemCount == 0 && results.loadState.refresh is LoadState.Loading ->
            CenteredProgress(Modifier.fillMaxSize())
        results.itemCount == 0 && results.loadState.refresh !is LoadState.Loading -> EmptySearch()
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results.itemCount) { index ->
                results[index]?.let { post ->
                    PostCard(post = post, showImage = true, onClick = { onOpen(post) })
                }
            }
            if (results.loadState.append is LoadState.Loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
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
