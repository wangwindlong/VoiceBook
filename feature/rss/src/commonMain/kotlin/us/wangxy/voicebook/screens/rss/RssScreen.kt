package us.wangxy.voicebook.screens.rss

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.window.core.layout.WindowWidthSizeClass
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.rss.RssPostModel

private val SidebarWidth = 300.dp
private val DrawerEdgeZone = 48.dp

/**
 * 资讯屏（Twine Home 风格）：自绘侧边栏（左缘加宽触发区、拖动跟手、阈值吸附、
 * 遮罩点击关闭）→ 精选大图横滑卡 → 分页文章列表；宽屏 list-detail 双栏，
 * 右栏面板内右滑可关闭返回列表。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RssScreen(
    active: Boolean,
    onOpenPost: (postId: String) -> Unit,
    onOpenFeeds: () -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSidebar: () -> Unit = {},
) {
    val viewModel = koinViewModel<RssViewModel>()
    // 只有 pager 稳定停在资讯页（active=true）才放行首屏取数/同步，滑入过程中不加载。
    LaunchedEffect(active) { if (active) viewModel.activate() }
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
                RssTopBar(
                    showSearch = showSearch,
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onSearch = viewModel::search,
                    onCloseSearch = { showSearch = false; viewModel.clearSearch() },
                    onOpenDrawer = { openDrawer() },
                    onMarkAllRead = { showMarkAllRead = true },
                    onStartSearch = { showSearch = true },
                    onOpenFeeds = onOpenFeeds,
                    onOpenSidebar = onOpenSidebar,
                )
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
                    // 过渡中（还没稳定停在资讯页）且尚无数据：先留白，避免加载态一闪而过
                    !active && posts.itemCount == 0 -> Box(Modifier.fillMaxSize())

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
                RssFeedDrawer(
                    filter = state.filter,
                    selectedFeedId = state.feedId,
                    feeds = feedsState.feeds,
                    onFilter = viewModel::setFilter,
                    onSelectFeed = { feedId ->
                        viewModel.setFeed(if (state.feedId == feedId) null else feedId)
                        closeDrawer()
                    },
                )
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
