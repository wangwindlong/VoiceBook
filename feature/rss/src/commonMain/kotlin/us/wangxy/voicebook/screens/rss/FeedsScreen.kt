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
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssSyncMode

/** 订阅管理：源列表增删 + 同步账户（本地 / Miniflux）。 */
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<FeedsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("订阅管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) { Text("添加") }
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("同步账户", style = MaterialTheme.typography.titleSmall)
                val mode = state.account?.mode ?: RssSyncMode.Local
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == RssSyncMode.Local,
                        onClick = { viewModel.saveAccount(RssAccountModel(mode = RssSyncMode.Local)) },
                        label = { Text("本地拉取") },
                    )
                    FilterChip(
                        selected = mode == RssSyncMode.Miniflux,
                        onClick = { showAccount = true },
                        label = { Text("Miniflux") },
                    )
                }
                if (mode == RssSyncMode.Miniflux) {
                    Text(
                        state.account?.serverUrl ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.savedAccounts.any { it.mode == RssSyncMode.Miniflux }) {
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

        HorizontalDivider()

        if (state.feeds.isEmpty()) {
            Text(
                "还没有订阅源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(state.feeds, key = { it.id }) { feed ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = false) {}
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(feed.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                feed.feedUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { deleting = feed.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
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
            initial = state.account,
            testing = state.testing,
            testResult = state.testResult,
            onTest = viewModel::testMiniflux,
            onSave = {
                viewModel.saveAccount(it)
                showAccount = false
            },
            onDismiss = { showAccount = false },
        )
    }
    deleting?.let { feedId ->
        AlertDialog(
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

@Composable
private fun AddFeedDialog(
    adding: Boolean,
    message: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅源地址（RSS / Atom）") },
                    singleLine = true,
                )
                if (adding) CircularProgressIndicator(Modifier.size(20.dp))
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !adding) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MinifluxAccountDialog(
    initial: RssAccountModel?,
    testing: Boolean,
    testResult: String?,
    onTest: (String, String) -> Unit,
    onSave: (RssAccountModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initial?.serverUrl ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Miniflux 账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址（https://...）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Token（设置 → API Keys）") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onTest(serverUrl.trim(), token.trim()) },
                        enabled = serverUrl.isNotBlank() && token.isNotBlank() && !testing,
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp)) else Text("测试连接")
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RssAccountModel(
                            mode = RssSyncMode.Miniflux,
                            serverUrl = serverUrl.trim(),
                            token = token.trim(),
                            lastEntryId = initial?.lastEntryId,
                            lastSyncedAt = initial?.lastSyncedAt ?: 0L,
                        ),
                    )
                },
                enabled = serverUrl.isNotBlank() && token.isNotBlank(),
            ) { Text("保存并同步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
