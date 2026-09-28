package us.wangxy.voicebook

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import us.wangxy.voicebook.audio.AudioPlayer
import kotlin.math.abs
import us.wangxy.voicebook.screens.rss.ArticleScreen
import us.wangxy.voicebook.screens.rss.FeedsScreen
import us.wangxy.voicebook.screens.rss.MiniPlayer
import us.wangxy.voicebook.screens.rss.RssScreen
import us.wangxy.voicebook.screens.ai.AiScreen
import us.wangxy.voicebook.screens.detail.DetailScreen
import us.wangxy.voicebook.screens.list.ListScreen
import us.wangxy.voicebook.screens.TwineDemoScreen
import us.wangxy.voicebook.screens.mine.MineScreen
import us.wangxy.voicebook.screens.auth.ForgotPasswordScreen
import us.wangxy.voicebook.screens.auth.LoginScreen
import us.wangxy.voicebook.screens.auth.RegisterScreen
import us.wangxy.voicebook.screens.reading.ReadingScreen
import us.wangxy.voicebook.screens.reader.ReaderScreen
import us.wangxy.voicebook.screens.voice.VoiceScreen
import us.wangxy.voicebook.theme.ProvideTheme
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.theme.ThemeMode
import us.wangxy.voicebook.theme.VoiceBookTheme
import us.wangxy.voicebook.ui.BottomTab
import us.wangxy.voicebook.ui.ReadingTab
import us.wangxy.voicebook.ui.UiPrefsController

/**
 * 单一主页 [MainDestination]：内容为外层 HorizontalPager（阅读/资讯/AI/我的，即底部
 * tab，可左右滑动切换），阅读页内部再有分段选择器 + 内层 Pager（书架/书城）。内层
 * 翻到书城末页继续左滑会链式驱动外层翻页；详情类页面（阅读器、文章、订阅管理、
 * 语音调试、Museum 示例）navigate 压栈其上。
 * 全局左侧抽屉（工具箱）由各屏顶栏按钮、左缘 48dp 或阅读内页书架右滑跟手拖拽唤出；
 * 右侧悬浮快捷面板仅在把手位置单击/左滑唤出。端点兜底手势以父级 Initial pass 实现，
 * 未接管的手势全部放行给子节点。
 */
@Serializable
data object MainDestination

@Serializable
data class ArticleDestination(val postId: String)

@Serializable
data object FeedsDestination

@Serializable
data object VoiceDestination

@Serializable
data object ListDestination

@Serializable
data object TwineDemoDestination

@Serializable
data object LoginDestination

@Serializable
data object RegisterDestination

@Serializable
data object ForgotPasswordDestination

@Serializable
data class DetailDestination(val objectId: Int)

@Serializable
data class ReaderDestination(
    val bookId: Int,
    val title: String,
    val author: String,
    val coverUrl: String,
    /** OPDS acquisition href from the shelf feed; empty when resuming from history. */
    val downloadHref: String = "",
)

/** Type-safe nav generates qualified-class routes; match them without reified helpers. */
private fun androidx.navigation.NavDestination?.routeContains(name: String): Boolean =
    this?.route?.contains(name) == true

private val BottomTab.label: String
    get() = when (this) {
        BottomTab.READING -> "阅读"
        BottomTab.RSS -> "资讯"
        BottomTab.AI -> "AI"
        BottomTab.MINE -> "我的"
    }

private val BottomTab.icon: ImageVector
    get() = when (this) {
        BottomTab.READING -> Icons.Filled.Home
        BottomTab.RSS -> Icons.Filled.Star
        BottomTab.AI -> Icons.Filled.Face
        BottomTab.MINE -> Icons.Filled.Person
    }

/**
 * 自适应外壳：窄屏（COMPACT）底部导航栏，宽屏 NavigationRail。阅读器/文章页沉浸推入
 * 并隐藏导航，保持沉浸阅读。
 */
@Composable
fun App() {
    val themeController = koinInject<ThemeController>()
    val seedState = koinInject<SeedColorState>()
    val uiPrefs = koinInject<UiPrefsController>()
    ProvideTheme(themeController) {
        VoiceBookTheme(seedState = seedState) {
            val navController: NavHostController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            val adaptiveInfo = currentWindowAdaptiveInfo()
            val useRail =
                adaptiveInfo.windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT
            val inReader = currentDestination?.routeContains("ReaderDestination") == true ||
                currentDestination?.routeContains("ArticleDestination") == true
            val onMain = currentDestination?.routeContains("MainDestination") == true
            val audioPlayer = koinInject<AudioPlayer>()
            val scope = rememberCoroutineScope()
            val sidebarState = remember { SidebarState() }
            val density = LocalDensity.current
            LaunchedEffect(density) {
                sidebarState.widthPx = with(density) { SidebarWidth.toPx() }
                sidebarState.velocityThresholdPx = with(density) { SidebarVelocityThreshold.toPx() }
            }

            var quickPanelExpanded by remember { mutableStateOf(false) }

            // 底部 tab 与阅读内页的 pager 状态提升到壳层，底栏/侧栏与其联动并持久化。
            val bottomPagerState = rememberPagerState(
                initialPage = uiPrefs.current.bottomTab.ordinal,
            ) { BottomTab.entries.size }
            val readingPagerState = rememberPagerState(
                initialPage = uiPrefs.current.readingTab.ordinal,
            ) { ReadingTab.entries.size }
            LaunchedEffect(bottomPagerState) {
                snapshotFlow { bottomPagerState.settledPage }
                    .collect { uiPrefs.setBottomTab(BottomTab.entries[it]) }
            }
            LaunchedEffect(readingPagerState) {
                snapshotFlow { readingPagerState.settledPage }
                    .collect { uiPrefs.setReadingTab(ReadingTab.entries[it]) }
            }

            fun switchTab(tab: BottomTab) {
                scope.launch { bottomPagerState.animateScrollToPage(tab.ordinal) }
            }

            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        if (!useRail && !inReader) {
                            Column {
                                MiniPlayer(audioPlayer)
                                NavigationBar {
                                    BottomTab.entries.forEach { tab ->
                                        NavigationBarItem(
                                            selected = bottomPagerState.settledPage == tab.ordinal,
                                            onClick = { switchTab(tab) },
                                            icon = { Icon(tab.icon, contentDescription = null) },
                                            label = { Text(tab.label) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Row(
                        Modifier
                            .fillMaxSize()
                            // 阅读页全幅纸面且自行避让系统栏:跳过 Scaffold 默认的系统栏
                            // 内边距,否则状态栏高度被垫两次,阅读标题会悬在半空
                            .padding(
                                if (currentDestination?.routeContains("ReaderDestination") == true) {
                                    PaddingValues(0.dp)
                                } else {
                                    padding
                                },
                            ),
                    ) {
                        if (useRail && !inReader) {
                            NavigationRail {
                                BottomTab.entries.forEach { tab ->
                                    NavigationRailItem(
                                        selected = bottomPagerState.settledPage == tab.ordinal,
                                        onClick = { switchTab(tab) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .then(
                                    if (onMain && !inReader) {
                                        // 端点兜底手势层挂在内容容器的父级：Initial pass 早于一切
                                        // 子节点的 Main pass，只在「右滑拉侧边栏」或「阅读内页已到
                                        // 书城末页且继续左滑」两种意图出现时才消费接管，其余手势
                                        // （点击、纵向滚动、内/外层翻页）一律放行，pager 行为与
                                        // 无此层时完全一致。
                                        Modifier.pointerInput(sidebarState) {
                                            endpointGestures(
                                                bottomPagerState = bottomPagerState,
                                                readingPagerState = readingPagerState,
                                                sidebarState = sidebarState,
                                                quickPanelOpen = { quickPanelExpanded },
                                                scope = scope,
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = MainDestination,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                composable<MainDestination> {
                                    MainContent(
                                        bottomPagerState = bottomPagerState,
                                        readingPagerState = readingPagerState,
                                        seedState = seedState,
                                        navigate = { navController.navigate(it) },
                                        onOpenSettings = { switchTab(BottomTab.MINE) },
                                        onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                    )
                                }
                                composable<ArticleDestination> { backStackEntry ->
                                    val route = backStackEntry.toRoute<ArticleDestination>()
                                    ArticleScreen(
                                        postId = route.postId,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable<FeedsDestination> {
                                    FeedsScreen(onBack = { navController.popBackStack() })
                                }
                                composable<VoiceDestination> {
                                    VoiceScreen(navigateBack = { navController.popBackStack() })
                                }
                                composable<ListDestination> {
                                    ListScreen(
                                        navigateToDetails = { objectId ->
                                            navController.navigate(DetailDestination(objectId))
                                        },
                                        navigateToVoice = { navController.navigate(VoiceDestination) },
                                        navigateToLibrary = { navController.popBackStack() },
                                    )
                                }
                                composable<TwineDemoDestination> {
                                    TwineDemoScreen(navigateBack = { navController.popBackStack() })
                                }
                                // 认证流：登录 ↔ 注册 / 重置密码 互相压栈，成功后都退回主页。
                                composable<LoginDestination> {
                                    LoginScreen(
                                        onBack = { navController.popBackStack() },
                                        onToRegister = { navController.navigate(RegisterDestination) },
                                        onForgotPassword = { navController.navigate(ForgotPasswordDestination) },
                                        onLoginSuccess = { navController.popBackStack(MainDestination, inclusive = false) },
                                    )
                                }
                                composable<RegisterDestination> {
                                    RegisterScreen(
                                        onBack = { navController.popBackStack() },
                                        onToLogin = { navController.popBackStack() },
                                        onRegisterSuccess = { navController.popBackStack(MainDestination, inclusive = false) },
                                    )
                                }
                                composable<ForgotPasswordDestination> {
                                    ForgotPasswordScreen(
                                        onBack = { navController.popBackStack() },
                                        onResetSuccess = { navController.popBackStack() },
                                    )
                                }
                                composable<DetailDestination> { backStackEntry ->
                                    DetailScreen(
                                        objectId = backStackEntry.toRoute<DetailDestination>().objectId,
                                        navigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable<ReaderDestination> { backStackEntry ->
                                    val route = backStackEntry.toRoute<ReaderDestination>()
                                    ReaderScreen(
                                        bookId = route.bookId,
                                        title = route.title,
                                        author = route.author,
                                        coverUrl = route.coverUrl,
                                        downloadHref = route.downloadHref,
                                        navigateBack = { navController.popBackStack() },
                                    )
                                }
                            }

                                if (onMain && !inReader) {
                                    // 右侧悬浮快捷面板：仅在把手位置可单击/左滑唤出，
                                    // 不再全屏拦截左滑（避免与底部 tab 翻页冲突）。
                                    QuickPanelOverlay(
                                        uiPrefs = uiPrefs,
                                        expanded = quickPanelExpanded,
                                        onExpandedChange = { quickPanelExpanded = it },
                                        actions = buildList {
                                            add(
                                                QuickAction("tab_reading", "去阅读", BottomTab.READING.icon) { switchTab(BottomTab.READING) },
                                            )
                                            add(
                                                QuickAction("tab_rss", "去资讯", BottomTab.RSS.icon) { switchTab(BottomTab.RSS) },
                                            )
                                            add(
                                                QuickAction("tab_ai", "去 AI", BottomTab.AI.icon) { switchTab(BottomTab.AI) },
                                            )
                                            add(
                                                QuickAction("tab_mine", "去我的", BottomTab.MINE.icon) { switchTab(BottomTab.MINE) },
                                            )
                                            add(
                                                QuickAction("open_sidebar", "打开侧边栏", Icons.Filled.Face) {
                                                    scope.launch { sidebarState.animateTo(true) }
                                                },
                                            )
                                            add(
                                                QuickAction("toggle_dark", "切换深浅色", Icons.Filled.Star) {
                                                    val current = themeController.preference.value.mode
                                                    themeController.setMode(
                                                        if (current == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark,
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                        }
                    }
                }

                SidebarOverlay(
                    state = sidebarState,
                    onOpenMine = {
                        scope.launch { sidebarState.animateTo(false) }
                        switchTab(BottomTab.MINE)
                    },
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    bottomPagerState: PagerState,
    readingPagerState: PagerState,
    seedState: SeedColorState,
    navigate: (Any) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    HorizontalPager(state = bottomPagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (BottomTab.entries[page]) {
            BottomTab.READING -> ReadingScreen(
                pagerState = readingPagerState,
                onOpenBook = { bookId, title, author, coverUrl, downloadHref ->
                    navigate(ReaderDestination(bookId, title, author, coverUrl, downloadHref))
                },
                onSeedColorChange = { seedState.update(it) },
                onOpenSettings = onOpenSettings,
                onOpenSidebar = onOpenSidebar,
            )
            BottomTab.RSS -> RssScreen(
                onOpenPost = { postId -> navigate(ArticleDestination(postId)) },
                onOpenFeeds = { navigate(FeedsDestination) },
                onSeedColorChange = { seedState.update(it) },
                onOpenSidebar = onOpenSidebar,
            )
            BottomTab.AI -> AiScreen(onOpenSidebar = onOpenSidebar)
            BottomTab.MINE -> MineScreen(
                onOpenVoiceDebug = { navigate(VoiceDestination) },
                onOpenMuseumDemo = { navigate(ListDestination) },
                onOpenSidebar = onOpenSidebar,
                onOpenTwineDemo = { navigate(TwineDemoDestination) },
                onOpenLogin = { navigate(LoginDestination) },
            )
        }
    }
}

/** 左缘侧边栏触发区宽度。 */
private val SidebarEdgeZone = 48.dp

/** 端点兜底手势层接管后的拖拽模式。 */
private sealed interface EndpointMode {
    /** 右滑拉出全局侧边栏（左缘触发区或 阅读内页书架 的任意位置）。 */
    data object SidebarPull : EndpointMode

    /** 阅读内页已在书城末页，继续左滑链式驱动外层底部 tab 翻页。 */
    data class ChainNext(val startPage: Int, val pageWidthPx: Float) : EndpointMode
}

/**
 * 主页内容容器的兜底手势层：以父级 Initial pass 监听全部按下，按意图分流——
 *  - 左缘 48dp（快捷面板与 RSS 页除外）或 阅读内页书架 的任意位置右滑：跟手拉出
 *    全局侧边栏，松手按速度/位置阈值结算；
 *  - 阅读内页 pager 已在书城（末页）且继续左滑：接管为 UserInput 滚动会话跟手驱动
 *    外层底部 tab 翻向资讯，松手按速度优先（系统最小 fling 速度）、距离兜底（40% 页宽）
 *    结算到整页，绝不停在中间，实现内→外链式翻页；
 *  - 其余手势一律不消费，子节点（点击、纵向滚动、内/外层 pager 翻页）行为不变。
 */
private suspend fun PointerInputScope.endpointGestures(
    bottomPagerState: PagerState,
    readingPagerState: PagerState,
    sidebarState: SidebarState,
    quickPanelOpen: () -> Boolean,
    scope: CoroutineScope,
) {
    val edgePullWidthPx = SidebarEdgeZone.toPx()
    // 链式翻页的 fling 判定用系统最小 fling 速度：轻甩即翻页，避免黏滞感
    val chainFlingPx = viewConfiguration.minimumFlingVelocity
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        // 从 down 起全程记录采样，保证轻甩（事件少）时速度也足够可信
        tracker.addPosition(down.uptimeMillis, down.position)
        var accumX = 0f
        var accumY = 0f
        var chainDx = 0f
        var mode: EndpointMode? = null
        var chainDeltas: Channel<Float>? = null
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (mode == null && (change.isConsumed || quickPanelOpen())) break
            val delta = change.positionChangeIgnoreConsumed()
            tracker.addPosition(change.uptimeMillis, change.position)
            when (mode) {
                null -> {
                    accumX += delta.x
                    accumY += delta.y
                    when {
                        // 右滑意图：仅当起点在侧边栏触发区（或阅读内页书架）时接管
                        accumX > slop && accumX > abs(accumY) -> {
                            val fromEdge = down.position.x < edgePullWidthPx
                            val fromShelf = bottomPagerState.currentPage == 0 &&
                                readingPagerState.currentPage == ReadingTab.SHELF.ordinal
                            val tabAllowsEdgePull =
                                BottomTab.entries[bottomPagerState.currentPage] != BottomTab.RSS
                            if (fromShelf || (fromEdge && tabAllowsEdgePull)) {
                                mode = EndpointMode.SidebarPull
                                change.consume()
                                // 补上越界判定期间的位移，避免起步跳变
                                scope.launch { sidebarState.dragBy(accumX) }
                            } else {
                                break
                            }
                        }
                        // 左滑意图：仅当阅读内页已到书城末页且外侧还有 tab 时链式接管
                        accumX < -slop && abs(accumX) > abs(accumY) -> {
                            val canChain = bottomPagerState.currentPage == 0 &&
                                readingPagerState.currentPage == ReadingTab.entries.lastIndex
                            if (canChain) {
                                mode = EndpointMode.ChainNext(
                                    startPage = bottomPagerState.currentPage,
                                    pageWidthPx = size.width.toFloat().coerceAtLeast(1f),
                                )
                                change.consume()
                                // 用 UserInput 滚动会话跟手驱动：可打断进行中的结算动画，
                                // 避免新旧两层位移互抢导致的卡顿
                                val deltas = Channel<Float>(capacity = Channel.UNLIMITED)
                                chainDeltas = deltas
                                scope.launch {
                                    bottomPagerState.scroll(MutatePriority.UserInput) {
                                        for (d in deltas) scrollBy(d)
                                    }
                                }
                                chainDx = accumX
                                deltas.trySend(-accumX)
                            } else {
                                break
                            }
                        }
                    }
                }
                EndpointMode.SidebarPull -> {
                    change.consume()
                    scope.launch { sidebarState.dragBy(delta.x) }
                }
                is EndpointMode.ChainNext -> {
                    change.consume()
                    chainDx += delta.x
                    chainDeltas?.trySend(-delta.x)
                }
            }
        }
        when (val claimed = mode) {
            EndpointMode.SidebarPull ->
                scope.launch { sidebarState.settle(tracker.calculateVelocity().x) }
            is EndpointMode.ChainNext -> {
                // 关闭输入结束会话（释放互斥），随后按速度优先、距离兜底结算到整页：
                // 快速前甩→翻页，反向甩动→回退，慢拖过 40%→翻页，否则回退，绝不停在中间
                chainDeltas?.close()
                chainDeltas = null
                scope.launch {
                    val vx = tracker.calculateVelocity().x
                    val target = when {
                        vx < -chainFlingPx -> claimed.startPage + 1
                        vx > chainFlingPx -> claimed.startPage
                        -chainDx > claimed.pageWidthPx * 0.4f -> claimed.startPage + 1
                        else -> claimed.startPage
                    }
                    bottomPagerState.animateScrollToPage(target)
                }
            }
            null -> Unit
        }
    }
}
