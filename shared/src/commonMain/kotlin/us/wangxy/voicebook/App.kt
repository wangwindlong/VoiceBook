package us.wangxy.voicebook

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import us.wangxy.voicebook.theme.ProvideTheme
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.theme.VoiceBookTheme
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import us.wangxy.voicebook.screens.detail.DetailScreen
import us.wangxy.voicebook.screens.library.LibraryScreen
import us.wangxy.voicebook.screens.list.ListScreen
import us.wangxy.voicebook.screens.reader.ReaderScreen
import us.wangxy.voicebook.screens.voice.VoiceScreen
import kotlinx.serialization.Serializable

@Serializable
object ListDestination

@Serializable
data class DetailDestination(val objectId: Int)

@Serializable
object VoiceDestination

@Serializable
object LibraryDestination

@Serializable
data class ReaderDestination(
    val bookId: Int,
    val title: String,
    val author: String,
    val coverUrl: String,
    /** OPDS acquisition href from the shelf feed; empty when resuming from history. */
    val downloadHref: String = "",
)

@Composable
fun App() {
    val themeController = koinInject<ThemeController>()
    ProvideTheme(themeController) {
        VoiceBookTheme {
            val navController: NavHostController = rememberNavController()
            NavHost(navController = navController, startDestination = ListDestination) {
                composable<ListDestination> {
                    ListScreen(
                        navigateToDetails = { objectId ->
                            navController.navigate(DetailDestination(objectId))
                        },
                        navigateToVoice = { navController.navigate(VoiceDestination) },
                        navigateToLibrary = { navController.navigate(LibraryDestination) },
                    )
                }
                composable<VoiceDestination> {
                    VoiceScreen(navigateBack = { navController.popBackStack() })
                }
                composable<DetailDestination> { backStackEntry ->
                    DetailScreen(
                        objectId = backStackEntry.toRoute<DetailDestination>().objectId,
                        navigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                composable<LibraryDestination> {
                    LibraryScreen(
                        navigateBack = { navController.popBackStack() },
                        navigateToReader = { bookId, title, author, coverUrl, downloadHref ->
                            navController.navigate(ReaderDestination(bookId, title, author, coverUrl, downloadHref))
                        },
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
        }
    }
}
