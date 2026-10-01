package us.wangxy.voicebook

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

private val PopupExitEasing = Easing { fraction ->
    1f - LinearOutSlowInEasing.transform(1f - fraction)
}

/** Classify the page being opened (or closed), so both sides share the same motion. */
internal enum class NavigationMotion {
    Horizontal, Bottom, Popup;

    fun enter(): EnterTransition = when (this) {
        Horizontal -> slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
        Bottom -> slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it }
        // Start moving on the first frame instead of easing in from a standstill.
        Popup -> scaleIn(tween(180, easing = LinearOutSlowInEasing), initialScale = 0.96f) +
            slideInVertically(tween(180, easing = LinearOutSlowInEasing)) { it / 40 }
    }

    fun exit(): ExitTransition = when (this) {
        Horizontal -> slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
        Bottom -> slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { it }
        // Replay the entrance backwards, including its easing and endpoints.
        Popup -> scaleOut(tween(180, easing = PopupExitEasing), targetScale = 0.96f) +
            slideOutVertically(tween(180, easing = PopupExitEasing)) { it / 40 }
    }

    // Modal pages cover their parent without moving it; hierarchical pages push it aside.
    fun parentExit(): ExitTransition = when (this) {
        Horizontal -> slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 }
        Bottom, Popup -> ExitTransition.None
    }

    fun parentEnter(): EnterTransition = when (this) {
        Horizontal -> slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 }
        Bottom, Popup -> EnterTransition.None
    }
}

internal fun NavDestination.navigationMotion(): NavigationMotion = when {
    hasRoute<ReaderDestination>() || hasRoute<LoginDestination>() -> NavigationMotion.Bottom
    hasRoute<ToolDestination>() || hasRoute<VoiceDestination>() ||
        hasRoute<TwineDemoDestination>() || hasRoute<BloomDemoDestination>() -> NavigationMotion.Popup
    else -> NavigationMotion.Horizontal
}
