package us.wangxy.voicebook

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kmp_app_template.shared.generated.resources.Res
import kmp_app_template.shared.generated.resources.home_mountain
import org.jetbrains.compose.resources.painterResource

/** A single panoramic source shared by all glass panels, including during a swipe. */
@Composable
internal fun HomeWallpaper(pagerState: PagerState?, glassState: HazeState) {
    val painter = painterResource(Res.drawable.home_mountain)
    val aspectRatio = painter.intrinsicSize.let { size ->
        if (size.width.isFinite() && size.height.isFinite() && size.height > 0f) {
            size.width / size.height
        } else {
            16f / 9f
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().hazeSource(glassState)) {
        // Preserve the image's full height on portrait screens and retain room to pan on wide screens.
        val imageWidth = maxOf(maxHeight * aspectRatio, maxWidth * 1.5f)
        val overflowPx = with(density) { (imageWidth - maxWidth).toPx() }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(imageWidth, maxHeight)
                .graphicsLayer {
                    // Reading the offset here updates drawing without recomposing the page content.
                    val page = pagerState?.let { it.currentPage + it.currentPageOffsetFraction } ?: 1f
                    translationX = -overflowPx * (page / 2f).coerceIn(0f, 1f)
                },
        )
    }
}
