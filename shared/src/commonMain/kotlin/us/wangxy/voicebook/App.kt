package us.wangxy.voicebook

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.rememberHazeState
import us.wangxy.voicebook.ui.widget.LocalGlassState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
fun App() {
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
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            val inImmersiveScreen = currentDestination?.routeContains("ReaderDestination") == true ||
            currentDestination?.routeContains("ArticleDestination") == true
            val audioPlayer = koinInject<AudioPlayer>()
            val listen = koinInject<ListenController>()
            val listenState by listen.state.collectAsStateWithLifecycle()
            LaunchedEffect(audioPlayer, listen) {
                audioPlayer.state.map { it.playing }.distinctUntilChanged().collect { if (it) listen.pause() }
            }
            LaunchedEffect(currentDestination?.routeContains("VoiceDestination")) {
                if (currentDestination?.routeContains("VoiceDestination") == true) listen.pause()
            }
            val scope = rememberCoroutineScope()
            val sidebarState = remember { SidebarState(SidebarSide.Right) }

            val onHome = currentDestination == null || currentDestination.routeContains("MainDestination")
            val hasWallpaper = onHome || currentDestination.routeContains("MineDestination")
            val homePagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
            val glassState = rememberHazeState()
            CompositionLocalProvider(LocalGlassState provides if (hasWallpaper) glassState else null) {
                Box(Modifier.fillMaxSize()) {
                    if (hasWallpaper) HomeWallpaper(
                        pagerState = if (onHome) homePagerState else null,
                        glassState = glassState,
                    )
                    Scaffold(
                        containerColor = if (hasWallpaper) Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                if (onHome || currentDestination?.routeContains("ReaderDestination") == true) {
                                    PaddingValues(0.dp)
                                } else {
                                    scaffoldPadding
                                },
                            ),
                        ) {
                            composable<MainDestination> {
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
                            composable<ReadingDestination> {
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
                            composable<RssDestination> {
                                RssScreen(
                                    active = true,
                                    onOpenPost = { navController.navigate(ArticleDestination(it)) },
                                    onOpenFeeds = { navController.navigate(FeedsDestination) },
                                    onSeedColorChange = { seedState.update(it) },
                                    onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } },
                                )
                            }
                            composable<AiDestination> {
                                AiScreen(onOpenSidebar = { scope.launch { sidebarState.animateTo(true) } })
                            }
                            composable<ToolDestination> { entry ->
                                LocalToolScreen(entry.toRoute<ToolDestination>().tool) { navController.popBackStack() }
                            }
                            composable<MineDestination> {
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
                            composable<ArticleDestination> { entry ->
                                ArticleScreen(
                                    postId = entry.toRoute<ArticleDestination>().postId,
                                    onOpenRelated = { navController.navigate(ArticleDestination(it)) },
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
                                    navigateToDetails = { navController.navigate(DetailDestination(it)) },
                                    navigateToVoice = { navController.navigate(VoiceDestination) },
                                    navigateToLibrary = { navController.navigate(ReadingDestination) },
                                )
                            }
                            composable<TwineDemoDestination> {
                                TwineDemoScreen(navigateBack = { navController.popBackStack() })
                            }
                            composable<BloomDemoDestination> {
                                BloomDemoScreen(navigateBack = { navController.popBackStack() })
                            }
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
                            composable<ChangePasswordDestination> {
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
                            composable<DetailDestination> { entry ->
                                DetailScreen(
                                    objectId = entry.toRoute<DetailDestination>().objectId,
                                    navigateBack = { navController.popBackStack() },
                                )
                            }
                            composable<ReaderDestination> { entry ->
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
