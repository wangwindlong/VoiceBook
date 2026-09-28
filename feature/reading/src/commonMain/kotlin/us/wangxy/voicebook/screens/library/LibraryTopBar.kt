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
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索书名或作者") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "搜索") }
                },
            )
            IconButton(onClick = onClearSearch) { Icon(Icons.Filled.Close, "取消搜索") }
        } else {
            IconButton(onClick = onOpenSidebar) { Icon(Icons.Filled.Build, contentDescription = "工具箱") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "刷新书架") }
        }
    }
}
