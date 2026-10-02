package us.wangxy.voicebook

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import us.wangxy.voicebook.audio.AudioPlayer
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.listen.ListenController
import us.wangxy.voicebook.listen.ListenMiniBar
import us.wangxy.voicebook.rss.RssRepository
import us.wangxy.voicebook.screens.BloomDemoScreen
import us.wangxy.voicebook.screens.TwineDemoScreen
import us.wangxy.voicebook.screens.ai.AiScreen
import us.wangxy.voicebook.screens.auth.ChangePasswordScreen
import us.wangxy.voicebook.screens.auth.ForgotPasswordScreen
import us.wangxy.voicebook.screens.auth.LoginScreen
import us.wangxy.voicebook.screens.auth.RegisterScreen
import us.wangxy.voicebook.screens.detail.DetailScreen
import us.wangxy.voicebook.screens.list.ListScreen
import us.wangxy.voicebook.screens.mine.MineScreen
import us.wangxy.voicebook.screens.reading.ReadingScreen
import us.wangxy.voicebook.screens.reader.ReaderScreen
import us.wangxy.voicebook.screens.rss.ArticleScreen
import us.wangxy.voicebook.screens.rss.FeedsScreen
import us.wangxy.voicebook.screens.rss.MiniPlayer
import us.wangxy.voicebook.screens.rss.RssScreen
import us.wangxy.voicebook.screens.voice.VoiceScreen
import us.wangxy.voicebook.theme.ProvideTheme
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.theme.VoiceBookTheme
import us.wangxy.voicebook.ui.ReadingTab
import us.wangxy.voicebook.ui.UiPrefsController

@Serializable data object MainDestination
@Serializable data object ReadingDestination
@Serializable data object RssDestination
@Serializable data object AiDestination
@Serializable data class ToolDestination(val tool: String)
@Serializable data object MineDestination
@Serializable data class ArticleDestination(val postId: String)
@Serializable data object FeedsDestination
@Serializable data object VoiceDestination
@Serializable data object ListDestination
@Serializable data object TwineDemoDestination
@Serializable data object BloomDemoDestination
@Serializable data object LoginDestination
@Serializable data object RegisterDestination
@Serializable data object ForgotPasswordDestination
@Serializable data object ChangePasswordDestination
@Serializable data class DetailDestination(val objectId: Int)
@Serializable
data class ReaderDestination(
    val bookId: Int,
    val title: String,
    val author: String,
    val coverUrl: String,
    val downloadHref: String = "",
)

private fun NavDestination?.routeContains(name: String): Boolean = this?.route?.contains(name) == true

@Composable
fun App(
    homeBackHandler: @Composable (enabled: Boolean, consumeBack: () -> Boolean) -> Unit = { _, _ -> },
) {
    val themeController = koinInject<ThemeController>()
    val seedState = koinInject<SeedColorState>()
    val uiPrefs = koinInject<UiPrefsController>()
    val session = koinInject<BffSession>()
    val bookRepository = koinInject<BookRepository>()
    val rssRepository = koinInject<RssRepository>()
    LaunchedEffect(session) {
        session.signedInUser.drop(1).collect { user ->
            bookRepository.onSessionChanged()
            rssRepository.onSessionChanged(signedIn = user != null)
        }
    }

    ProvideTheme(themeController) {
        VoiceBookTheme(seedState = seedState) {
            ReadingSyncHost()
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            val inImmersiveScreen = currentDestination?.routeContains("ReaderDestination") == true ||
            currentDestination?.routeContains("ArticleDestination") == true
            val audioPlayer = koinInject<AudioPlayer>()
            val listen = koinInject<ListenController>()
            val listenState by listen.state.collectAsStateWithLifecycle()
            LaunchedEffect(listen, session, bookRepository) {
                launch { session.signedInUser.drop(1).collect { listen.stop() } }
                launch { bookRepository.serverVersion.drop(1).collect { listen.stop() } }
            }
            LaunchedEffect(audioPlayer, listen) {
                audioPlayer.state.map { it.playing }.distinctUntilChanged().collect { if (it) listen.pause() }
            }
            LaunchedEffect(currentDestination?.routeContains("VoiceDestination")) {
                if (currentDestination?.routeContains("VoiceDestination") == true) listen.pause()
            }
            val scope = rememberCoroutineScope()
            val sidebarState = remember { SidebarState(SidebarSide.Right) }

            val homePagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
            // Each destination supplies its own background inside the animated page.
            androidx.compose.runtime.CompositionLocalProvider(
                us.wangxy.voicebook.ui.widget.LocalGlassState provides null,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                        bottomBar = {
                            if (!inImmersiveScreen) {
                                Column {
                                    ListenMiniBar(listenState, listen) {
                                        val bookId = listenState.bookId ?: return@ListenMiniBar
                                        navController.navigate(
                                            ReaderDestination(bookId, listenState.title, listenState.author, listenState.coverUrl),
                                        )
                                    }
                                    MiniPlayer(audioPlayer)
                                }
                            }
                        },
                    ) { scaffoldPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = MainDestination,
                            enterTransition = { targetState.destination.navigationMotion().enter() },
                            exitTransition = { targetState.destination.navigationMotion().parentExit() },
                            popEnterTransition = { initialState.destination.navigationMotion().parentEnter() },
                            popExitTransition = { initialState.destination.navigationMotion().exit() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            pageComposable<MainDestination>(homePagerState, scaffoldPadding) {
                                HomeScreen(
                                    pagerState = homePagerState,
                                    contentPadding = scaffoldPadding,
                                    onSidebarNavigate = { destination ->
                                        when (destination) {
                                            "reading" -> navController.navigate(ReadingDestination)
                                            "rss" -> navController.navigate(RssDestination)
                                            "ai" -> navController.navigate(AiDestination)
                                            "login" -> navController.navigate(LoginDestination)
                                            "settings" -> navController.navigate(MineDestination)
                                            "debug" -> navController.navigate(VoiceDestination)
                                            else -> navController.navigate(ToolDestination(destination))
                                        }
                                    },
                                    rssRepository = rssRepository,
                                    onOpenBook = { id, title, author, cover ->
                                        navController.navigate(ReaderDestination(id, title, author, cover))
                                    },
                                    onSeedColorChange = { seedState.update(it) },
                                    onOpenLibrary = { navController.navigate(ReadingDestination) },
                                    onOpenRss = { navController.navigate(RssDestination) },
                                    onOpenAi = { navController.navigate(AiDestination) },
                                    onOpenMine = { navController.navigate(MineDestination) },
                                    onOpenVoiceDebug = { navController.navigate(VoiceDestination) },
                                    onOpenMuseumDemo = { navController.navigate(ListDestination) },
                                    onOpenTwineDemo = { navController.navigate(TwineDemoDestination) },
                                    onOpenBloomDemo = { navController.navigate(BloomDemoDestination) },
                                    onOpenLogin = { navController.navigate(LoginDestination) },
                                    onOpenChangePassword = { navController.navigate(ChangePasswordDestination) },
                                    onOpenPost = { navController.navigate(ArticleDestination(it)) },
                                    onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                )
                            }
                            pageComposable<ReadingDestination>(homePagerState, scaffoldPadding) {
                                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                                    initialPage = uiPrefs.current.readingTab.ordinal,
                                ) { ReadingTab.entries.size }
                                LaunchedEffect(pagerState) {
                                    snapshotFlow { pagerState.settledPage }
                                        .collect { uiPrefs.setReadingTab(ReadingTab.entries[it]) }
                                }
                                ReadingScreen(
                                    pagerState = pagerState,
                                    onOpenBook = { id, title, author, cover, href ->
                                        navController.navigate(ReaderDestination(id, title, author, cover, href))
                                    },
                                    onSeedColorChange = { seedState.update(it) },
                                    onOpenSettings = { navController.navigate(MineDestination) },
                                    onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                )
                            }
                            pageComposable<RssDestination>(homePagerState, scaffoldPadding) {
                                RssScreen(
                                    active = true,
                                    onOpenPost = { navController.navigate(ArticleDestination(it)) },
                                    onOpenFeeds = { navController.navigate(FeedsDestination) },
                                    onSeedColorChange = { seedState.update(it) },
                                    onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                )
                            }
                            pageComposable<AiDestination>(homePagerState, scaffoldPadding) {
                                AiScreen(onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } })
                            }
                            pageComposable<ToolDestination>(homePagerState, scaffoldPadding) { entry ->
                                LocalToolScreen(entry.toRoute<ToolDestination>().tool) { navController.popBackStack() }
                            }
                            pageComposable<MineDestination>(homePagerState, scaffoldPadding) {
                                MineScreen(
                                    onBack = { navController.popBackStack() },
                                    onOpenVoiceDebug = { navController.navigate(VoiceDestination) },
                                    onOpenMuseumDemo = { navController.navigate(ListDestination) },
                                    onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                    onOpenTwineDemo = { navController.navigate(TwineDemoDestination) },
                                    onOpenBloomDemo = { navController.navigate(BloomDemoDestination) },
                                    onOpenLogin = { navController.navigate(LoginDestination) },
                                    onOpenChangePassword = { navController.navigate(ChangePasswordDestination) },
                                )
                            }
                            pageComposable<ArticleDestination>(homePagerState, scaffoldPadding) { entry ->
                                ArticleScreen(
                                    postId = entry.toRoute<ArticleDestination>().postId,
                                    onOpenRelated = { navController.navigate(ArticleDestination(it)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            pageComposable<FeedsDestination>(homePagerState, scaffoldPadding) {
                                FeedsScreen(onBack = { navController.popBackStack() })
                            }
                            pageComposable<VoiceDestination>(homePagerState, scaffoldPadding) {
                                VoiceScreen(navigateBack = { navController.popBackStack() })
                            }
                            pageComposable<ListDestination>(homePagerState, scaffoldPadding) {
                                ListScreen(
                                    navigateToDetails = { navController.navigate(DetailDestination(it)) },
                                    navigateToVoice = { navController.navigate(VoiceDestination) },
                                    navigateToLibrary = { navController.navigate(ReadingDestination) },
                                )
                            }
                            pageComposable<TwineDemoDestination>(homePagerState, scaffoldPadding) {
                                TwineDemoScreen(navigateBack = { navController.popBackStack() })
                            }
                            pageComposable<BloomDemoDestination>(homePagerState, scaffoldPadding) {
                                BloomDemoScreen(navigateBack = { navController.popBackStack() })
                            }
                            pageComposable<LoginDestination>(homePagerState, scaffoldPadding) {
                                LoginScreen(
                                    onBack = { navController.popBackStack() },
                                    onToRegister = { navController.navigate(RegisterDestination) },
                                    onForgotPassword = { navController.navigate(ForgotPasswordDestination) },
                                    onLoginSuccess = { navController.popBackStack(MainDestination, inclusive = false) },
                                )
                            }
                            pageComposable<RegisterDestination>(homePagerState, scaffoldPadding) {
                                RegisterScreen(
                                    onBack = { navController.popBackStack() },
                                    onToLogin = { navController.popBackStack() },
                                    onRegisterSuccess = { navController.popBackStack(MainDestination, inclusive = false) },
                                )
                            }
                            pageComposable<ForgotPasswordDestination>(homePagerState, scaffoldPadding) {
                                ForgotPasswordScreen(
                                    onBack = { navController.popBackStack() },
                                    onResetSuccess = { navController.popBackStack() },
                                )
                            }
                            pageComposable<ChangePasswordDestination>(homePagerState, scaffoldPadding) {
                                ChangePasswordScreen(
                                    onBack = { navController.popBackStack() },
                                    onChanged = { navController.popBackStack() },
                                    onSessionExpired = {
                                        navController.navigate(LoginDestination) {
                                            popUpTo(MainDestination) { inclusive = false }
                                        }
                                    },
                                )
                            }
                            pageComposable<DetailDestination>(homePagerState, scaffoldPadding) { entry ->
                                DetailScreen(
                                    objectId = entry.toRoute<DetailDestination>().objectId,
                                    navigateBack = { navController.popBackStack() },
                                )
                            }
                            pageComposable<ReaderDestination>(homePagerState, scaffoldPadding) { entry ->
                                val route = entry.toRoute<ReaderDestination>()
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
                    }
                    // Register after NavHost so the root-page rule takes priority on Android.
                    homeBackHandler(currentDestination?.routeContains("MainDestination") == true) {
                        when {
                            sidebarState.open || sidebarState.progress.value > 0f -> {
                                scope.launch { sidebarState.animateTo(false) }
                                true
                            }
                            homePagerState.currentPage != 1 || homePagerState.targetPage != 1 ||
                                homePagerState.isScrollInProgress -> {
                                scope.launch { homePagerState.animateScrollToPage(1) }
                                true
                            }
                            else -> false
                        }
                    }
                    SidebarOverlay(
                        state = sidebarState,
                        onNavigate = { destination ->
                            scope.launch { sidebarState.animateTo(false) }
                            when (destination) {
                                "reading" -> navController.navigate(ReadingDestination)
                                "rss" -> navController.navigate(RssDestination)
                                "ai" -> navController.navigate(AiDestination)
                                "login" -> navController.navigate(LoginDestination)
                                "settings" -> navController.navigate(MineDestination)
                                "debug" -> navController.navigate(VoiceDestination)
                                else -> navController.navigate(ToolDestination(destination))
                            }
                        },
                        onOpenMine = {
                            scope.launch { sidebarState.animateTo(false) }
                            navController.navigate(MineDestination)
                        },
                    )
                }
            }
        }
    }
}
