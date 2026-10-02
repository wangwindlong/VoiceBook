package us.wangxy.voicebook.ui.widget

import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** App-wide continuous progress track without Material's trailing stop marker. */
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
) {
    MaterialLinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
) {
    MaterialLinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        gapSize = 0.dp,
    )
}
