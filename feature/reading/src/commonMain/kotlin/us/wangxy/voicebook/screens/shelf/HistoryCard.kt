package us.wangxy.voicebook.screens.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.screens.book.BookCover

@Composable
internal fun HistoryCard(
    entry: HistoryEntry,
    authHeader: String?,
    onClick: () -> Unit,
) {
    Card(Modifier.width(120.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BookCover(
                coverUrl = entry.coverUrl,
                title = entry.title,
                authHeader = authHeader,
                placeholderChars = 2,
                placeholderColor = LocalContentColor.current,
            )
            Text(
                entry.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { entry.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "已读 ${entry.progress}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
