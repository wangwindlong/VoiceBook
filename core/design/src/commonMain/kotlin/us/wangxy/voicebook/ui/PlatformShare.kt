package us.wangxy.voicebook.ui
import androidx.compose.runtime.Composable

/** Android launches the native chooser; other hosts copy a shareable title and link. */
@Composable
expect fun rememberShareText(): (String, String) -> Unit
