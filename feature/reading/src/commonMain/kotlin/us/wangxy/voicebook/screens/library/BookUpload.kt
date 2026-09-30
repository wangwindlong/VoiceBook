package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import us.wangxy.voicebook.bff.ContentApi
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.ui.widget.BloomSheet
import us.wangxy.voicebook.ui.widget.ReferenceFilters

data class SelectedBook(val name: String, val bytes: ByteArray)

@Composable
expect fun rememberBookPicker(onResult: (Result<SelectedBook>) -> Unit): () -> Unit

@Composable
fun BookUploadSheet(onUploaded: () -> Unit, onDismiss: () -> Unit) {
    val api = koinInject<ContentApi>()
    val session = koinInject<BffSession>()
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<SelectedBook?>(null) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("电子书") }
    var uploading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberBookPicker { result ->
        result.onSuccess { file = it; title = it.name.substringBeforeLast('.'); message = null }
            .onFailure { message = it.message ?: "无法读取文件" }
    }
    BloomSheet(visible = true, onDismiss = { if (!uploading) onDismiss() }, peekFraction = 0.9f) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("上传图书", style = MaterialTheme.typography.titleLarge)
            Surface(onClick = { if (!uploading) picker() }, modifier = Modifier.fillMaxWidth().height(160.dp),
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("↑", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(file?.name ?: "点击选择图书", style = MaterialTheme.typography.bodyMedium)
                    Text("EPUB、PDF、TXT · 最大 64MB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !uploading, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(author, { author = it }, label = { Text("作者（选填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !uploading, shape = RoundedCornerShape(12.dp))
            ReferenceFilters(listOf("电子书", "经典", "文学", "科幻"), category, { if (!uploading) category = it })
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val selected = file ?: return@Button
                uploading = true; message = null
                scope.launch {
                    try { api.upload(selected.name, title, author, category, selected.bytes); onUploaded(); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { uploading = false }
                }
            }, enabled = file != null && title.isNotBlank() && !uploading && session.signedInUser.collectAsState().value != null,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                if (uploading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (session.signedInUser.value == null) "登录后上传" else "上传")
            }
        }
    }
}
