package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.ui.widget.BloomSheet

@Composable
internal fun TocSheet(
    chapters: List<EpubBook.Chapter>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BloomSheet(
        visible = true,
        onDismiss = onDismiss,
        peekFraction = 0.7f,
    ) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp).padding(bottom = 12.dp)) {
            items(chapters.size) { index ->
                val chapter = chapters[index]
                Text(
                    chapter.title.ifBlank { "第 ${index + 1} 节" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
