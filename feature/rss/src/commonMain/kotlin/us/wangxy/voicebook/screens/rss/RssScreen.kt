package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlin.math.abs
import kotlin.math.roundToInt
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssPostsFilter
import us.wangxy.voicebook.twine.TwineMenu
import us.wangxy.voicebook.twine.TwineMenuItem
import us.wangxy.voicebook.twine.TwineMenuSeparator
import kotlin.time.ExperimentalTime

private val SidebarWidth = 300.dp
private val DrawerEdgeZone = 48.dp
private val DismissThreshold = 120.dp

/**
 * 资讯屏（Twine Home 风格）：自绘侧边栏（左缘加宽触发区、拖动跟手、阈值吸附、
 * 遮罩点击关闭）→ 精选大图横滑卡 → 分页文章列表；宽屏 list-detail 双栏，
 * 右栏面板内右滑可关闭返回列表。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalTime::class)
@Composable
fun RssScreen(
    onOpenPost: (postId: String) -> Unit,
    onOpenFeeds: () -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSidebar: () -> Unit = {},
) {
    val viewModel = koinViewModel<RssViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedsState by viewModel.feeds.collectAsStateWithLifecycle()
    val errorMessage by viewModel.error.collectAsStateWithLifecycle()
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheetWidthPx = with(density) { SidebarWidth.toPx() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    var showSearch by remember { mutableStateOf(false) }
    var showMarkAllRead by remember { mutableStateOf(false) }

    fun settleDrawer(target: Float) {
        scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = drawerProgress,
                targetValue = target,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            ) { value, _ -> drawerProgress = value }
        }
    }
    fun openDrawer() = settleDrawer(1f)
    fun closeDrawer() = settleDrawer(0f)

    val onOpen: (RssPostModel) -> Unit = { post ->
        onSeedColorChange(post.seedColor)
        viewModel.markRead(post)
        onOpenPost(post.id)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.pointerInput(sheetWidthPx) {
                // 左缘 48dp 内起手的横向拖动打开侧边栏；拖过半宽自动吸附展开，否则收回。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > with(density) { DrawerEdgeZone.toPx() }) return@awaitEachGesture
                    val touchSlop = viewConfiguration.touchSlop
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalX = 0f
                    var totalY = 0f
                    var tracking = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val dx = change.position.x - lastX
                        val dy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        if (!tracking) {
                            totalX += dx
                            totalY += dy
                            when {
                                abs(totalX) > touchSlop && abs(totalX) >= abs(totalY) -> tracking = true
                                abs(totalY) > touchSlop -> return@awaitEachGesture // 纵向手势让位给列表滚动
                            }
                        }
                        if (tracking) {
                            change.consume()
                            drawerProgress = (drawerProgress + dx / sheetWidthPx).coerceIn(0f, 1f)
                        }
                        if (change.changedToUp()) {
                            if (tracking) settleDrawer(if (drawerProgress >= 0.5f) 1f else 0f)
                            break
                        }
                    }
                }
            },
            topBar = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    IconButton(onClick = { openDrawer() }) {
                        Icon(Icons.Filled.Menu, contentDescription = "订阅源")
                    }
                    if (showSearch) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("搜索文章") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = viewModel::search) { Icon(Icons.Filled.Search, "搜索") }
                            },
                        )
                        IconButton(onClick = { showSearch = false; viewModel.clearSearch() }) {
                            Icon(Icons.Filled.Close, "取消搜索")
                        }
                    } else {
                        Text("资讯", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        // Twine 风溢出菜单:纸面浮层 + 荧光按压高亮(替代原"全部已读"平铺按钮)
                        var menuExpanded by remember { mutableStateOf(false) }
                        TwineMenu(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                            anchor = {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                                }
                            },
                        ) {
                            TwineMenuItem("全部标为已读", onClick = { showMarkAllRead = true })
                            TwineMenuSeparator()
                            TwineMenuItem("搜索文章", onClick = { showSearch = true })
                            TwineMenuItem("管理订阅", onClick = onOpenFeeds)
                        }
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = onOpenSidebar) {
                            Icon(Icons.Filled.Build, contentDescription = "工具箱")
                        }
                    }
                }
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.syncing,
                onRefresh = {
                    viewModel.refresh()
                },
                modifier = Modifier.padding(padding),
            ) {
                when {
                    state.searchResults != null -> SearchResults(
                        results = state.searchResults.orEmpty(),
                        searching = state.searching,
                        onOpen = onOpen,
                    )

                    posts.itemCount == 0 && posts.loadState.refresh is androidx.paging.LoadState.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                    posts.itemCount == 0 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            errorMessage ?: "没有文章，添加订阅源开始阅读吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> RssContent(
                        posts = posts,
                        twoPane = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass !=
                            WindowWidthSizeClass.COMPACT,
                        onOpen = onOpen,
                        onSeedColorChange = onSeedColorChange,
                    )
                }
            }
        }

        // 遮罩：跟随进度渐显；点击关闭；上面的拖动也能收放。
        if (drawerProgress > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * drawerProgress))
                    .pointerInput(sheetWidthPx) {
                        detectTapGestures { closeDrawer() }
                    }
                    .pointerInput(sheetWidthPx) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                total += dragAmount
                                drawerProgress = (drawerProgress + dragAmount / sheetWidthPx).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                settleDrawer(if (drawerProgress >= 0.5f) 1f else 0f)
                                total = 0f
                            },
                        )
                    },
            )
        }

        // 侧边栏面板：明确背景色；本体可拖动，超过半宽自动吸附展开/收起。
        if (drawerProgress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(SidebarWidth)
                    .offset(x = -SidebarWidth * (1f - drawerProgress))
                    .shadow(if (drawerProgress > 0.1f) 12.dp else 0.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .statusBarsPadding()
                    .pointerInput(sheetWidthPx) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                total += dragAmount
                                drawerProgress = (drawerProgress + dragAmount / sheetWidthPx).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                settleDrawer(if (drawerProgress >= 0.5f) 1f else 0f)
                                total = 0f
                            },
                        )
                    },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text("订阅源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    FilterChip(
                        selected = state.filter == RssPostsFilter.Unread,
                        onClick = { viewModel.setFilter(RssPostsFilter.Unread) },
                        label = { Text("未读") },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = state.filter == RssPostsFilter.All,
                        onClick = { viewModel.setFilter(RssPostsFilter.All) },
                        label = { Text("全部") },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (feedsState.feeds.isEmpty()) {
                        Text(
                            "还没有订阅源",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn {
                        items(feedsState.feeds, key = { it.feedId }) { feed ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setFeed(if (state.feedId == feed.feedId) null else feed.feedId)
                                        closeDrawer()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    feed.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (state.feedId == feed.feedId) FontWeight.Bold else FontWeight.Normal,
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
        }
    }

    if (showMarkAllRead) {
        AlertDialog(
            onDismissRequest = { showMarkAllRead = false },
            title = { Text("全部标为已读？") },
            confirmButton = {
                Button(onClick = {
                    viewModel.markAllRead()
                    showMarkAllRead = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showMarkAllRead = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalTime::class)
@Composable
private fun RssContent(
    posts: androidx.paging.compose.LazyPagingItems<RssPostModel>,
    twoPane: Boolean,
    onOpen: (RssPostModel) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
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
                                    if (total > with(dragDensity) { DismissThreshold.toPx() }) {
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

/** 精选大图横滑卡（Twine FeaturedSection 的简化版）。 */
@Composable
private fun FeaturedSection(featured: List<RssPostModel>, onOpen: (RssPostModel) -> Unit) {
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

@Composable
private fun PostList(
    posts: androidx.paging.compose.LazyPagingItems<RssPostModel>,
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
            PostCard(post = post, showImage = showFeaturedImages || post.imageUrl != null, onClick = { onOpen(post) })
        }
        if (posts.loadState.append is androidx.paging.LoadState.Loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

/** 圆角卡片：来源·时间 / 标题（未读加粗）/ 摘要 / 尾部缩略图。 */
@Composable
private fun PostCard(post: RssPostModel, showImage: Boolean, onClick: () -> Unit) {
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

/** 宽屏右栏预览。 */
@Composable
private fun PostPreview(post: RssPostModel, onOpen: () -> Unit) {
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

@Composable
private fun SearchResults(
    results: List<RssPostModel>,
    searching: Boolean,
    onOpen: (RssPostModel) -> Unit,
) {
    when {
        searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
        Text("没有匹配的文章", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 相对时间（x 分钟前 / 小时前 / 天前），纯 common 计算。 */
fun relativeTime(publishedAt: Long): String {
    val diff = kotlin.time.Clock.System.now().toEpochMilliseconds() - publishedAt
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / 60 / 24} 天前"
        else -> "${minutes / 60 / 24 / 30} 个月前"
    }
}
