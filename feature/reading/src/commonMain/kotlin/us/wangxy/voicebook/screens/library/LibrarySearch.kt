package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.screens.EmptyScreenContent
import us.wangxy.voicebook.screens.book.BookCell
import us.wangxy.voicebook.ui.widget.CenteredProgress

@Composable
internal fun LibrarySetupCard(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接 calibre-web 服务器", style = MaterialTheme.typography.titleMedium)
            Text(
                "填入服务器地址（如 http://192.168.1.10:8083）和开启 OPDS 权限的账号，即可浏览书架并阅读 EPUB。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("去配置") }
        }
    }
}

@Composable
internal fun LibrarySearchGrid(
    results: List<OpdsEntry>,
    searching: Boolean,
    authHeader: String?,
    coverUrl: (OpdsEntry) -> String,
    onOpen: (OpdsEntry) -> Unit,
) {
    when {
        searching -> CenteredProgress(Modifier.fillMaxSize())

        results.isEmpty() -> EmptyScreenContent(Modifier.fillMaxSize())

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.bookId }) { entry ->
                BookCell(
                    title = entry.title,
                    author = entry.author,
                    coverUrl = coverUrl(entry),
                    authHeader = authHeader,
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}
