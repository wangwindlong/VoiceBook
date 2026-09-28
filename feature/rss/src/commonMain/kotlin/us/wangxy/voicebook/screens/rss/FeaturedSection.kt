package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import us.wangxy.voicebook.rss.RssPostModel

/** 精选大图横滑卡（Twine FeaturedSection 的简化版）。 */
@Composable
internal fun FeaturedSection(featured: List<RssPostModel>, onOpen: (RssPostModel) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { featured.size })
    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) { page ->
        val post = featured[page]
        Card(
            Modifier
                .fillMaxSize()
                .clickable { onOpen(post) },
            shape = RoundedCornerShape(14.dp),
        ) {
            Box {
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = post.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.75f),
                            ),
                        ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(
                        post.feedTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        post.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}
