package us.wangxy.voicebook.ui
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
@Composable
actual fun rememberShareText(): (String, String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { title, link -> clipboard.setText(AnnotatedString("$title\n$link")) }
}
