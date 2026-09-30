package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.screens.book.BookCover
import us.wangxy.voicebook.ui.widget.BloomSheet

@Composable
fun BookDetailsSheet(book: CachedBook, authHeader: String?, onRead: () -> Unit, onDismiss: () -> Unit) {
    var comments by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var detail by remember(book.bookId) { mutableStateOf<us.wangxy.voicebook.bff.contract.CalibreBook?>(null) }
    val api = org.koin.compose.koinInject<us.wangxy.voicebook.bff.ContentApi>()
    LaunchedEffect(book.bookId) {
        try { detail = api.book(book.bookId.toLong()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Cached metadata is sufficient for direct/offline libraries. */ }
    }
    val controller = rememberReaderComments(book.bookId, book.title)
    val clipboard = LocalClipboardManager.current
    BloomSheet(visible = true, onDismiss = onDismiss, peekFraction = 0.65f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BookCover(book.coverUrl, book.title, authHeader, Modifier.width(100.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    Text(book.author.ifBlank { "作者未提供" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(book.epubHref.trimEnd('/').substringAfterLast('/').ifBlank { "电子书" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            detail?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it.replace(Regex("<[^>]*>"), ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
            }
            Button(onRead, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("开始阅读") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { controller.open(); comments = true }) { Text("查看评论") }
                TextButton(onClick = { clipboard.setText(AnnotatedString("《${book.title}》 ${book.author}")); copied = true }) { Text(if (copied) "已复制书籍信息" else "分享书籍") }
            }
        }
    }
    if (comments) ReaderCommentsSheet(controller) { comments = false }
}

/** The same comment page works for books and articles, including replies and captcha. */
@Composable
fun ContentComments(pageKey: String, title: String, onDismiss: () -> Unit) {
    val vm = org.koin.compose.viewmodel.koinViewModel<ReaderCommentsViewModel>()
    val controller = remember(vm, pageKey, title) { ReaderCommentsController(vm, pageKey, title) }
    LaunchedEffect(pageKey) { controller.open() }
    ReaderCommentsSheet(controller, onDismiss)
}
