package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: (() -> Unit)?,
    onSettings: () -> Unit,
    /** Starts reading aloud from the current page; null where listening isn't available (PDF). */
    onListen: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onListen != null) {
            IconButton(onClick = onListen) {
                Text("听", style = MaterialTheme.typography.titleMedium)
            }
        }
        IconButton(onClick = onSettings) {
            Text("Aa", style = MaterialTheme.typography.titleMedium)
        }
        if (onToc != null) {
            IconButton(onClick = onToc) { Icon(Icons.Filled.List, "目录") }
        }
    }
}

@Composable
internal fun ReaderBottomBar(
    page: Int,
    pageCount: Int,
    chapter: Int,
    chapterCount: Int,
    percent: Int,
    pageFraction: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    fontSize: Int,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Slider(
            value = pageFraction.coerceIn(0f, 1f),
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$chapter / $chapterCount 章 · $page / $pageCount 页 · $percent%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onFontSmaller, enabled = fontSize > ReaderFontSizes.first()) {
                Text("A-", style = MaterialTheme.typography.titleMedium)
            }
            Text("${fontSize}pt", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onFontLarger, enabled = fontSize < ReaderFontSizes.last()) {
                Text("A+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
