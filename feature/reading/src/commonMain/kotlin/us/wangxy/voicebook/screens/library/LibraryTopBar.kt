package us.wangxy.voicebook.screens.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun LibraryTopBar(
    searching: Boolean,
    query: String,
    refreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    androidx.compose.foundation.layout.Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("书城", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "刷新书架") }
            IconButton(onClick = onOpenSidebar) { Icon(Icons.Filled.Build, "更多工具") }
        }
        us.wangxy.voicebook.ui.widget.ReferenceSearch(query, onQueryChange, "搜索书籍或作者")
    }
}
