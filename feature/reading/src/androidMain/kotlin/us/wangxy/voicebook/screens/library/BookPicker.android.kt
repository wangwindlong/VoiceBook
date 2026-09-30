package us.wangxy.voicebook.screens.library

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberBookPicker(onResult: (Result<SelectedBook>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "book.epub"
                require(name.substringAfterLast('.').lowercase() in listOf("epub", "pdf", "txt")) { "请选择 EPUB、PDF 或 TXT 图书" }
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= 64L * 1024 * 1024) { "图书超过 64MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                    ?: error("无法打开文件")
                require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "图书为空或超过 64MB" }
                SelectedBook(name, bytes)
            } }
            callback(result)
        }
    }
    return { launcher.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain")) }
}
