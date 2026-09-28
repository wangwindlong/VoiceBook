package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import us.wangxy.voicebook.rss.RssPostModel

internal val RssDetailDismissThreshold = 120.dp

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun RssContent(
    posts: LazyPagingItems<RssPostModel>,
    twoPane: Boolean,
    onOpen: (RssPostModel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val navigator = rememberListDetailPaneScaffoldNavigator<RssPostModel>()

    val featured = remember(posts.itemCount) {
        (0 until posts.itemCount).mapNotNull { posts[it] }.filter { it.imageUrl != null }.take(8)
    }

    val listPane: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            if (featured.isNotEmpty()) {
                FeaturedSection(featured = featured, onOpen = onOpen)
            }
            PostList(
                posts = posts,
                showFeaturedImages = featured.isEmpty(),
                onOpen = onOpen,
            )
        }
    }

    if (!twoPane) {
        listPane()
        return
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            listPane()
        },
        detailPane = {
            val selected = navigator.currentDestination?.contentKey
            var dismissDrag by remember { mutableStateOf(0f) }
            val dragDensity = LocalDensity.current
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(dismissDrag.roundToInt(), 0) }
                    .shadow(if (dismissDrag > 0f) 8.dp else 0.dp)
                    .pointerInput(selected) {
                        // 面板内右滑：跟手位移，超过阈值自动关闭返回列表。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touchSlop = viewConfiguration.touchSlop
                            var lastX = down.position.x
                            var total = 0f
                            var tracking = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val dx = change.position.x - lastX
                                lastX = change.position.x
                                if (!tracking) {
                                    total += dx
                                    if (total > touchSlop) tracking = true
                                }
                                if (tracking) {
                                    change.consume()
                                    total += dx
                                    dismissDrag = total.coerceAtLeast(0f)
                                }
                                if (change.changedToUp()) {
                                    if (total > with(dragDensity) { RssDetailDismissThreshold.toPx() }) {
                                        scope.launch { navigator.navigateBack() }
                                    }
                                    dismissDrag = 0f
                                    break
                                }
                            }
                        }
                    },
            ) {
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "选择一篇文章",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    PostPreview(post = selected, onOpen = { onOpen(selected) })
                }
            }
        },
    )
}
