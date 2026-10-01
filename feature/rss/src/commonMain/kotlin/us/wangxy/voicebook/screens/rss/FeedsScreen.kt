package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import us.wangxy.voicebook.ui.widget.ReferenceSearch
import us.wangxy.voicebook.ui.widget.ReferenceFilters
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.ui.widget.BloomDialog
import us.wangxy.voicebook.rss.RssSyncMode

/** 订阅管理：源列表增删 + 资讯同步（本地抓取 / 已登录走统一账号 / 未登录可连自己的 Miniflux）。 */
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<FeedsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var showSync by remember { mutableStateOf(false) }
    var categoryFeed by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("订阅源", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSync = !showSync }) { Text("设置") }
        }

        ReferenceSearch(query, { query = it }, "搜索订阅源")
        ReferenceFilters(listOf("全部", "新闻", "科技", "财经", "娱乐", "未分类"), category, { category = it })
        if (showSync) Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("资讯同步", style = MaterialTheme.typography.titleSmall)
                val mode = if (state.signedIn) RssSyncMode.Miniflux else state.account?.mode ?: RssSyncMode.Local
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == RssSyncMode.Local,
                        enabled = !state.signedIn,
                        onClick = { viewModel.disableUnifiedNews() },
                        label = { Text("本地抓取") },
                    )
                    FilterChip(
                        selected = mode == RssSyncMode.Miniflux,
                        onClick = { if (state.signedIn) viewModel.enableUnifiedNews() else showAccount = true },
                        label = { Text(if (state.signedIn) "统一账号" else "Miniflux") },
                    )
                }
                Text(
                    when {
                        state.signedIn && mode == RssSyncMode.Miniflux -> "已开启：经统一账号同步（服务端抓取，换设备登录即可继续阅读）。"
                        state.signedIn -> "当前为本地抓取：订阅源与已读状态只保存在这台设备上。"
                        mode == RssSyncMode.Miniflux -> state.account?.serverUrl.orEmpty()
                        else -> "未登录：可连接自己的 Miniflux 服务器；在「我的」登录后自动改走统一账号。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.signedIn && mode == RssSyncMode.Miniflux) {
                    OutlinedButton(onClick = { viewModel.testMiniflux() }, enabled = !state.testing) {
                        if (state.testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
                    }
                }
                if (!state.signedIn && state.savedAccounts.any { it.mode == RssSyncMode.Miniflux }) {
                    Text("已保存的账号", style = MaterialTheme.typography.labelMedium)
                    state.savedAccounts
                        .filter { it.mode == RssSyncMode.Miniflux }
                        .forEach { saved ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    saved.label + if (saved.serverUrl == state.account?.serverUrl) "（当前）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.activateRssAccount(saved.id) },
                                )
                                IconButton(onClick = { viewModel.deleteRssAccount(saved.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                }
                state.testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it == "连接成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val filtered = state.feeds.filter {
            (query.isBlank() || it.title.contains(query, true) || it.feedUrl.contains(query, true)) &&
                (category == "全部" || state.categories[it.id] == category)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            if (filtered.isEmpty()) item { Text("暂无匹配订阅源", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp)) }
            items(filtered, key = { it.id }) { feed ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(feed.title.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(feed.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            Text(state.categories[feed.id] ?: "未分类", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { categoryFeed = feed.id }.padding(vertical = 4.dp))
                            DropdownMenu(expanded = categoryFeed == feed.id, onDismissRequest = { categoryFeed = null }) {
                                listOf("新闻", "科技", "财经", "娱乐", "未分类").forEach { label ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setCategory(feed.id, label); categoryFeed = null })
                                }
                            }
                        }
                    }
                    Surface(onClick = { deleting = feed.id }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text("✓ 已订阅", modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Surface(onClick = { showAdd = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) { Text("＋ 添加订阅源", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (showAdd) {
        AddFeedDialog(
            adding = state.adding,
            message = state.message,
            onAdd = viewModel::addFeed,
            onDismiss = { showAdd = false },
        )
    }
    if (showAccount) {
        MinifluxAccountDialog(
            initial = state.account?.takeIf { it.token != null },
            testing = state.testing,
            testResult = state.testResult,
            onTest = viewModel::testMinifluxAccount,
            onSave = {
                viewModel.saveAccount(it)
                showAccount = false
            },
            onDismiss = { showAccount = false },
        )
    }
    deleting?.let { feedId ->
        BloomDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除订阅源？") },
            text = { Text("将同时删除该源已缓存的文章。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.removeFeed(feedId)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
