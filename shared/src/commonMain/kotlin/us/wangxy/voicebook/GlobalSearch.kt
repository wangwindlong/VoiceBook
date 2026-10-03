package us.wangxy.voicebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.rss.*
import us.wangxy.voicebook.screens.shelf.ShelfViewModel
import us.wangxy.voicebook.ui.UiPrefsController
import us.wangxy.voicebook.ui.widget.GlobalBottomSheet
import us.wangxy.voicebook.ui.widget.GlobalUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun GlobalSearch(state: GlobalUiState, onBook: (Int, String, String, String) -> Unit, onPost: (String) -> Unit, onShortcut: (String) -> Unit) {
    if (!state.searchVisible) return
    val prefs = koinInject<UiPrefsController>()
    val preferences by prefs.prefs.collectAsStateWithLifecycle()
    val repository = koinInject<BookRepository>()
    val rss = koinInject<RssRepository>()
    val shelf = koinViewModel<ShelfViewModel>()
    val server by shelf.server.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    fun closeAnd(action: () -> Unit) { prefs.recordSearch(query); state.searchVisible = false; action() }
    LaunchedEffect(query, server) {
        results = emptyList(); error = null; loading = query.isNotBlank()
        if (query.isBlank()) return@LaunchedEffect
        delay(300)
        val found = mutableListOf<SearchResult>()
        try {
            server?.let { backend ->
                repository.search(backend, query.trim()).forEach { book ->
                    val cover = book.coverHref?.let { if (it.startsWith("http")) it else backend.root + it }.orEmpty()
                    found += SearchResult(book.title, book.author, { onBook(book.bookId, book.title, book.author, cover) })
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "部分书籍结果暂时不可用" }
        try {
            rss.postsPage(20, 0, RssListQuery(searchText = query.trim())).forEach { post ->
                found += SearchResult(post.title, post.feedTitle, { onPost(post.id) })
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "部分搜索结果暂时不可用，请稍后重试" }
        results = found; loading = false
    }
    GlobalBottomSheet(true, { state.searchVisible = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("全局搜索", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { state.searchVisible = false }) { Text("关闭") }
        }
        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), placeholder = { Text("搜索书籍、资讯…") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { prefs.recordSearch(query) }))
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最近搜索", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = prefs::clearSearchHistory, enabled = preferences.searchHistory.isNotEmpty()) { Text("清空") }
                }
                if (preferences.searchHistory.isEmpty()) Text("暂无搜索记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                preferences.searchHistory.forEach { term -> TextButton(onClick = { query = term }) { Text(term) } }
                Text("推荐探索", style = MaterialTheme.typography.titleSmall)
                Row { listOf("文学", "科技", "历史").forEach { term -> TextButton(onClick = { query = term }) { Text(term) } } }
                Text("快捷操作", style = MaterialTheme.typography.titleSmall)
                Row { listOf("reading" to "我的书架", "rss" to "订阅资讯", "ai" to "AI 助手").forEach { (id, title) ->
                    TextButton(onClick = { state.searchVisible = false; onShortcut(id) }) { Text(title) }
                } }
            } else {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && results.isEmpty()) Text("没有找到相关内容")
                results.forEach { result ->
                    Column(Modifier.fillMaxWidth().clickable { closeAnd(result.open) }.padding(vertical = 10.dp)) {
                        Text(result.title, style = MaterialTheme.typography.bodyLarge)
                        Text(result.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
private data class SearchResult(val title: String, val subtitle: String, val open: () -> Unit)
