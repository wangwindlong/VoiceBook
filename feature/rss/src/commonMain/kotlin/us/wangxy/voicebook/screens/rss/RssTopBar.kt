package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.twine.TwineMenu
import us.wangxy.voicebook.twine.TwineMenuItem
import us.wangxy.voicebook.twine.TwineMenuSeparator

@Composable
internal fun RssTopBar(
    showSearch: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenDrawer: () -> Unit,
    onMarkAllRead: () -> Unit,
    onStartSearch: () -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Filled.Menu, contentDescription = "订阅源")
        }
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索文章") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "搜索") }
                },
            )
            IconButton(onClick = onCloseSearch) {
                Icon(Icons.Filled.Close, "取消搜索")
            }
        } else {
            Text("资讯", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            var menuExpanded by remember { mutableStateOf(false) }
            TwineMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                anchor = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                },
            ) {
                TwineMenuItem("全部标为已读", onClick = onMarkAllRead)
                TwineMenuSeparator()
                TwineMenuItem("搜索文章", onClick = onStartSearch)
                TwineMenuItem("管理订阅", onClick = onOpenFeeds)
            }
            IconButton(onClick = onStartSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Filled.Build, contentDescription = "工具箱")
            }
        }
    }
}
