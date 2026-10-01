package us.wangxy.voicebook

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.chrisbanes.haze.rememberHazeState
import us.wangxy.voicebook.ui.widget.LocalGlassState

/** Background and insets belong to each back-stack entry and move with its content. */
internal inline fun <reified T : Any> NavGraphBuilder.pageComposable(
    homePagerState: PagerState,
    scaffoldPadding: PaddingValues,
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val animationScope = this
        val destination = entry.destination
        val home = destination.hasRoute<MainDestination>()
        val wallpaper = home || destination.hasRoute<MineDestination>()
        val glassState = rememberHazeState()
        Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)) {
            if (wallpaper) {
                HomeWallpaper(if (home) homePagerState else null, glassState)
            }
            CompositionLocalProvider(LocalGlassState provides if (wallpaper) glassState else null) {
                Box(
                    Modifier.fillMaxSize().padding(
                        if (home || destination.hasRoute<ReaderDestination>()) PaddingValues(0.dp)
                        else scaffoldPadding,
                    ),
                ) {
                    content(animationScope, entry)
                }
            }
        }
    }
}
