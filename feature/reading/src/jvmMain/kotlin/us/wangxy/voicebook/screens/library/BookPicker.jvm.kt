package us.wangxy.voicebook.screens.library

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberBookPicker(onResult: (Result<SelectedBook>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return { scope.launch {
        val file = withContext(Dispatchers.IO) {
            val picker = JFileChooser().apply { fileFilter = FileNameExtensionFilter("图书 EPUB/PDF/TXT", "epub", "pdf", "txt") }
            if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
        }
        if (file != null) onResult(withContext(Dispatchers.IO) { runCatching {
            require(file.length() in 1..(64L * 1024 * 1024)) { "图书为空或超过 64MB" }
            SelectedBook(file.name, file.readBytes())
        } })
    } }
}
