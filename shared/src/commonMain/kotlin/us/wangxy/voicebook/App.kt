package us.wangxy.voicebook

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
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
import us.wangxy.voicebook.screens.BloomDemoScreen
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
import us.wangxy.voicebook.ui.nested.rememberChildFirstNestedScroll

/**
 * 单一主页 [MainDestination]：内容为外层 HorizontalPager（阅读/资讯/AI/我的，即底部
 * tab，可左右滑动切换），阅读页内部再有分段选择器 + 内层 Pager（书架/书城）。内层
 * 翻到书城末页继续左滑会链式驱动外层翻页；详情类页面（阅读器、文章、订阅管理、
 * 语音调试、Museum 示例）navigate 压栈其上。
 * 全局左侧抽屉（工具箱）由各屏顶栏按钮、左缘 48dp，或子树没接住的右滑跟手拖拽唤出。
 * 横向滚动容器自动作为嵌套滚动子节点先消费，剩余位移交给壳层的
 * [us.wangxy.voicebook.ui.nested.ChildFirstNestedScrollConnection]；内外层 Pager
 * 还能滚时由它们接住，都到头之后才拉侧边栏。侧边栏接住后，这次手势不再传给底下的列表。
 * 右侧悬浮快捷面板仅在把手位置单击/左滑唤出。
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
data object BloomDemoDestination

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
            val sidebarState = remember { SidebarState(SidebarSide.Left) }
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

            // 不绑定具体页面：任意横向子滚动先消费，剩余的手指向右才拉侧边栏。
            // 一旦侧边栏接住，同一次手势的后续左右位移都只驱动侧边栏。
            val tabFlingGate = remember { TabFlingGate() }
            val sidebarNestedScroll = rememberChildFirstNestedScroll(
                onUnconsumedDrag = { available, captured ->
                    val dx = available.x
                    if (quickPanelExpanded || (!captured && dx <= 0f) || dx == 0f) {
                        Offset.Zero
                    } else {
                        tabFlingGate.sidebarTook = true
                        scope.launch { sidebarState.dragBy(dx) }
                        Offset(dx, 0f)
                    }
                },
                onUnconsumedFling = { available ->
                    scope.launch { sidebarState.settle(available.x) }
                    available
                },
            )
            val shellNestedScroll = remember(sidebarNestedScroll, tabFlingGate) {
                object : NestedScrollConnection by sidebarNestedScroll {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (
                            source == NestedScrollSource.UserInput &&
                            (consumed.x != 0f || available.x != 0f)
                        ) {
                            tabFlingGate.sawHorizontalNested = true
                        }
                        return sidebarNestedScroll.onPostScroll(consumed, available, source)
                    }
                }
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
                                        // 嵌套滚动在任意子滚动之后接收剩余位移；指针层只在
                                        // 没有子节点接住时才接管（边缘拉侧边栏、书城末页链式翻页）。
                                        Modifier
                                            .nestedScroll(shellNestedScroll)
                                            .pointerInput(sidebarState) {
                                                endpointGestures(
                                                    bottomPagerState = bottomPagerState,
                                                    readingPagerState = readingPagerState,
                                                    sidebarState = sidebarState,
                                                    quickPanelOpen = { quickPanelExpanded },
                                                    tabFlingGate = tabFlingGate,
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
                                composable<BloomDemoDestination> {
                                    BloomDemoScreen(navigateBack = { navController.popBackStack() })
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
    HorizontalPager(
        state = bottomPagerState,
        modifier = Modifier.fillMaxSize(),
        flingBehavior = rememberTabFlingBehavior(bottomPagerState),
        pageNestedScrollConnection = rememberFlingForwardingConnection(bottomPagerState),
    ) { page ->
        when (BottomTab.entries[page]) {
            BottomTab.READING -> ReadingScreen(
                pagerState = readingPagerState,
                flingBehavior = rememberTabFlingBehavior(readingPagerState),
                pageNestedScrollConnection = rememberFlingForwardingConnection(readingPagerState),
                onOpenBook = { bookId, title, author, coverUrl, downloadHref ->
                    navigate(ReaderDestination(bookId, title, author, coverUrl, downloadHref))
                },
                onSeedColorChange = { seedState.update(it) },
                onOpenSettings = onOpenSettings,
                onOpenSidebar = onOpenSidebar,
            )
            BottomTab.RSS -> RssScreen(
                // 仅在 pager 停止滑动、且当前就停在资讯页时才放行加载，
                // 避免滑入过程中的取数把翻页手势带偏、切到一半又弹回。
                active = !bottomPagerState.isScrollInProgress &&
                    bottomPagerState.currentPage == BottomTab.RSS.ordinal,
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
                onOpenBloomDemo = { navigate(BloomDemoDestination) },
                onOpenLogin = { navigate(LoginDestination) },
            )
        }
    }
}

/** 左缘侧边栏触发区宽度。 */
private val SidebarEdgeZone = 48.dp

/**
 * 主界面底部 tab 翻页的 fling 速度阈值：比系统默认（400dp/s）更小，
 * 让手指轻甩即可顺畅切换到相邻 tab。
 */
private val BottomTabFlingVelocity = 160.dp

/**
 * Compose Pager 内部判定「速度足够高、必定翻向同方向下一页」所用的最小 fling 速度
 * （对应 foundation 的 MinFlingVelocityDp = 400dp）。这里显式复刻该常量，
 * 用于把低于它但快于 [BottomTabFlingVelocity] 的速度抬到阈值，从而降低翻页所需的速度门槛。
 */
private val SystemMinFlingVelocity = 400.dp

/** 链式翻页的距离兜底阈值：拖动超过该比例页宽即认定翻页，否则回退。 */
private const val ChainSettleThreshold = 0.4f

/**
 * 为底部 tab 分页器构造降低速度门槛的 [TargetedFlingBehavior]：沿用 Pager 默认的吸附动画，
 * 仅在松手速度落在 [BottomTabFlingVelocity, SystemMinFlingVelocity) 区间时，把速度方向保留、
 * 数值抬到系统最小 fling 阈值，使轻甩也能确定地翻到相邻页；其余速度原样透传，行为不变。
 */
@Composable
private fun rememberLoweredThresholdFlingBehavior(state: PagerState): TargetedFlingBehavior {
    val density = LocalDensity.current
    val delegate = PagerDefaults.flingBehavior(state = state)
    val loweredPx = with(density) { BottomTabFlingVelocity.toPx() }
    val systemMinPx = with(density) { SystemMinFlingVelocity.toPx() }
    return remember(delegate, loweredPx, systemMinPx) {
        LoweredThresholdFlingBehavior(delegate, loweredPx, systemMinPx)
    }
}

/**
 * 标签页 fling：速度门槛与空白处直接甩 Pager 相同；已经在该方向边界上时把速度原样交还，
 * 让外层 Pager 继续翻，而不是在当前页把 fling 吃掉。
 */
@Composable
private fun rememberTabFlingBehavior(state: PagerState): TargetedFlingBehavior {
    val lowered = rememberLoweredThresholdFlingBehavior(state)
    return remember(state, lowered) {
        EdgePassthroughFlingBehavior(
            delegate = lowered,
            canScrollForward = { state.canScrollForward },
            canScrollBackward = { state.canScrollBackward },
        )
    }
}

/**
 * Pager 默认的 [PagerDefaults.pageNestedScrollConnection] 会在 [NestedScrollConnection.onPostFling]
 * 里吞掉横轴速度且不翻页。这里改成不消费，剩余 fling 交给 Pager 自己的 scrollable，
 * 这样列表滚到头之后的甩动和空白处一样能切到下一页。
 */
@Composable
private fun rememberFlingForwardingConnection(state: PagerState): NestedScrollConnection {
    val delegate = PagerDefaults.pageNestedScrollConnection(state, Orientation.Horizontal)
    return remember(delegate) {
        object : NestedScrollConnection by delegate {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity.Zero
        }
    }
}

/**
 * 本次手势里是否已经有横向嵌套滚动，或侧边栏已经接住。
 * 纵向列表不会上报横轴速度，松手时要靠它判断能不能补一次翻页。
 */
private class TabFlingGate {
    var sawHorizontalNested = false
    var sidebarTook = false
}

/**
 * 正速度表示往前翻。当前方向已经到头时不跑吸附动画，把速度交回嵌套 fling。
 */
private class EdgePassthroughFlingBehavior(
    private val delegate: TargetedFlingBehavior,
    private val canScrollForward: () -> Boolean,
    private val canScrollBackward: () -> Boolean,
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val blocked = (initialVelocity > 0f && !canScrollForward()) ||
            (initialVelocity < 0f && !canScrollBackward())
        if (blocked) return initialVelocity
        return with(delegate) { performFling(initialVelocity, onRemainingDistanceUpdated) }
    }
}

private class LoweredThresholdFlingBehavior(
    private val delegate: TargetedFlingBehavior,
    private val loweredThresholdPx: Float,
    private val systemMinFlingPx: Float,
) : TargetedFlingBehavior {

    /** 低于门槛原样返回；介于两者之间则按方向抬到系统阈值；已达阈值原样返回。 */
    private fun boost(velocity: Float): Float {
        val magnitude = abs(velocity)
        return when {
            magnitude >= systemMinFlingPx -> velocity
            magnitude >= loweredThresholdPx ->
                if (velocity < 0f) -systemMinFlingPx else systemMinFlingPx
            else -> velocity
        }
    }

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val effective = boost(initialVelocity)
        val remaining = with(delegate) {
            performFling(effective, onRemainingDistanceUpdated)
        }
        // 放大的速度只用于翻页方向判定；向嵌套/链式滚动回传时按原比例还原，避免速度被放大。
        return if (effective == initialVelocity || effective == 0f) {
            remaining
        } else {
            remaining * (initialVelocity / effective)
        }
    }
}

/** 端点兜底手势层接管后的拖拽模式。 */
private sealed interface EndpointMode {
    /** 右滑拉出全局侧边栏（左缘触发区，或 pager 已在起点且没有子滚动接住的区域）。 */
    data object SidebarPull : EndpointMode

    /** 阅读内页已在书城末页，继续左滑链式驱动外层底部 tab 翻页。 */
    data class ChainNext(val startPage: Int, val pageWidthPx: Float) : EndpointMode
}

/**
 * 主页内容容器的兜底手势层。先让子节点在 Main pass 处理（横向列表、Pager 会消费），
 * 只有子节点没接住时才在 Final pass 接管——
 *  - 左缘 48dp（快捷面板与 RSS 页除外），或内外层 pager 都已在起点、且没有子滚动
 *    接住的右滑：跟手拉出全局侧边栏，松手按速度/位置阈值结算。能横向滚动的子节点
 *    自己消费；滚到头之后的剩余量沿嵌套滚动链上交，由壳层连接接手，接手后同一次
 *    手势不再传给子节点；
 *  - 阅读内页 pager 已在书城（末页）且继续左滑、且内层没接住：接管为 UserInput
 *    滚动会话跟手驱动外层底部 tab 翻向资讯。内层接住时，剩余量走 Pager 自己的嵌套滚动；
 *  - 纵向列表接住了手势时，横轴速度到不了 Pager。松手若是明确的横向 fling，
 *    按空白处同一速度门槛补一次翻页；
 *  - 其余手势一律不消费，子节点（点击、纵向滚动、内/外层 pager 翻页）行为不变。
 */
private suspend fun PointerInputScope.endpointGestures(
    bottomPagerState: PagerState,
    readingPagerState: PagerState,
    sidebarState: SidebarState,
    quickPanelOpen: () -> Boolean,
    tabFlingGate: TabFlingGate,
    scope: CoroutineScope,
) {
    val edgePullWidthPx = SidebarEdgeZone.toPx()
    val tabFlingPx = BottomTabFlingVelocity.toPx()
    // 链式翻页的 fling 判定用系统最小 fling 速度：轻甩即翻页，避免黏滞感
    val chainFlingPx = viewConfiguration.minimumFlingVelocity
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        tabFlingGate.sawHorizontalNested = false
        tabFlingGate.sidebarTook = false
        val tracker = VelocityTracker()
        // 从 down 起全程记录采样，保证轻甩（事件少）时速度也足够可信
        tracker.addPosition(down.uptimeMillis, down.position)
        var accumX = 0f
        var accumY = 0f
        var chainDx = 0f
        var mode: EndpointMode? = null
        var childTookPointer = false
        var chainDeltas: Channel<Float>? = null
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (mode != null) {
                // 已经接管：在子节点 Main pass 之前吃掉，避免列表和侧边栏一起动。
                val delta = change.positionChangeIgnoreConsumed()
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                when (mode) {
                    EndpointMode.SidebarPull ->
                        scope.launch { sidebarState.dragBy(delta.x) }
                    is EndpointMode.ChainNext -> {
                        chainDx += delta.x
                        chainDeltas?.trySend(-delta.x)
                    }
                }
                continue
            }
            if (change.isConsumed || quickPanelOpen()) break

            // Final pass 时子节点的 Main 已结束。子节点接住后不再抢指针，
            // 但继续记速度：纵向列表不会把横轴 fling 交上去，松手时再补翻页。
            val afterChildren = awaitPointerEvent(PointerEventPass.Final)
            val settled = afterChildren.changes.firstOrNull { it.id == down.id } ?: break
            if (!settled.pressed) break
            if (settled.isConsumed) {
                childTookPointer = true
                tracker.addPosition(settled.uptimeMillis, settled.position)
                continue
            }

            val delta = settled.positionChangeIgnoreConsumed()
            tracker.addPosition(settled.uptimeMillis, settled.position)
            accumX += delta.x
            accumY += delta.y
            when {
                // 右滑意图：子节点没接住。内外层 pager 都在起点时，没有更内层的
                // 横向滚动能接住反向位移，由壳层拉侧边栏；否则只认左缘触发区。
                accumX > slop && accumX > abs(accumY) -> {
                    val fromEdge = down.position.x < edgePullWidthPx
                    val pagersAtStart = bottomPagerState.currentPage == 0 &&
                        readingPagerState.currentPage == 0
                    val tabAllowsEdgePull =
                        BottomTab.entries[bottomPagerState.currentPage] != BottomTab.RSS
                    if (pagersAtStart || (fromEdge && tabAllowsEdgePull)) {
                        mode = EndpointMode.SidebarPull
                        settled.consume()
                        // 补上越界判定期间的位移，避免起步跳变
                        scope.launch { sidebarState.dragBy(accumX) }
                    } else {
                        break
                    }
                }
                // 左滑意图：仅当阅读内页已到书城末页且外侧还有 tab、且内层没接住时链式接管
                accumX < -slop && abs(accumX) > abs(accumY) -> {
                    val canChain = bottomPagerState.currentPage == 0 &&
                        readingPagerState.currentPage == ReadingTab.entries.lastIndex
                    if (canChain) {
                        mode = EndpointMode.ChainNext(
                            startPage = bottomPagerState.currentPage,
                            pageWidthPx = size.width.toFloat().coerceAtLeast(1f),
                        )
                        settled.consume()
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
                        -chainDx > claimed.pageWidthPx * ChainSettleThreshold -> claimed.startPage + 1
                        else -> claimed.startPage
                    }
                    bottomPagerState.animateScrollToPage(target)
                }
            }
            null -> if (childTookPointer && !quickPanelOpen()) {
                val velocity = tracker.calculateVelocity()
                offerTabFling(
                    velocityX = velocity.x,
                    velocityY = velocity.y,
                    thresholdPx = tabFlingPx,
                    gate = tabFlingGate,
                    bottomPagerState = bottomPagerState,
                    readingPagerState = readingPagerState,
                    scope = scope,
                )
            }
        }
    }
}

/**
 * 纵向列表把横轴速度丢掉之后的补救：松手速度达到标签页 fling 门槛，
 * 且这次手势没有横向子滚动或侧边栏参与时，翻最近的一层标签页。
 */
private fun offerTabFling(
    velocityX: Float,
    velocityY: Float,
    thresholdPx: Float,
    gate: TabFlingGate,
    bottomPagerState: PagerState,
    readingPagerState: PagerState,
    scope: CoroutineScope,
) {
    if (gate.sawHorizontalNested || gate.sidebarTook) return
    if (abs(velocityX) < thresholdPx || abs(velocityX) < abs(velocityY)) return
    if (bottomPagerState.isScrollInProgress || readingPagerState.isScrollInProgress) return
    val forward = velocityX < 0f
    val onReading = bottomPagerState.currentPage == BottomTab.READING.ordinal
    if (onReading) {
        val innerTarget = readingPagerState.currentPage + if (forward) 1 else -1
        if (innerTarget in 0 until readingPagerState.pageCount) {
            scope.launch { readingPagerState.animateScrollToPage(innerTarget) }
            return
        }
    }
    val bottomTarget = bottomPagerState.currentPage + if (forward) 1 else -1
    if (bottomTarget in 0 until bottomPagerState.pageCount) {
        scope.launch { bottomPagerState.animateScrollToPage(bottomTarget) }
    }
}
