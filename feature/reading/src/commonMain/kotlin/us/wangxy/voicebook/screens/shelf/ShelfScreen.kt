package us.wangxy.voicebook.screens.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.screens.book.BookCell
import us.wangxy.voicebook.screens.library.BookUploadSheet
import us.wangxy.voicebook.screens.reader.BookDetailsSheet
import us.wangxy.voicebook.ui.widget.ReferenceSearch
import us.wangxy.voicebook.ui.widget.ReferenceFilters

@Composable
fun ShelfScreen(
    onOpenBook: (Int, String, String, String, String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenSidebar: () -> Unit = {},
    onBrowseLibrary: () -> Unit = {},
) {
    val viewModel = koinViewModel<ShelfViewModel>()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val selectedTags by viewModel.tags.collectAsStateWithLifecycle()
    val tagMode by viewModel.tagMode.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val immediate by viewModel.immediate.collectAsStateWithLifecycle()
    val filtering by viewModel.filtering.collectAsStateWithLifecycle()
    val books = viewModel.books.collectAsLazyPagingItems()
    LaunchedEffect(books.loadState.refresh) { if(books.loadState.refresh is LoadState.NotLoading) viewModel.filtersApplied() }
    val authHeader = server?.let(viewModel::coverAuthHeader)
    var upload by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CachedBook?>(null) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的书架", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "书架操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("浏览书城") }, onClick = { menu = false; onBrowseLibrary() })
                        DropdownMenuItem(text = { Text("刷新书架") }, onClick = { menu = false; viewModel.refresh() })
                        DropdownMenuItem(text = { Text("更多工具") }, onClick = { menu = false; onOpenSidebar() })
                    }
                }
            }
            ReferenceSearch(query, viewModel::setQuery, "搜索书籍或作者")
            ReferenceFilters(listOf("全部", "电子书"), category, { viewModel.category.value = it })
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                var showSort by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick={showSort=true}) { Text("排序："+mapOf("added" to "入库时间","title" to "书名","author" to "作者","rating" to "评分","pubdate" to "出版日期")[sort]) }
                    DropdownMenu(showSort,{showSort=false}) {
                        mapOf("added" to "入库时间","title" to "书名","author" to "作者","rating" to "评分","pubdate" to "出版日期").forEach { (key,label) ->
                            DropdownMenuItem(text={Text(label)},onClick={viewModel.sort.value=key;showSort=false})
                        }
                    }
                }
                TextButton(onClick={viewModel.tagMode.value=if(tagMode=="any") "all" else "any"}) { Text(if(tagMode=="all") "匹配全部标签" else "匹配任一标签") }
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                items(availableTags.size) { index -> val tag=availableTags[index]
                    FilterChip(selected=tag.name in selectedTags,onClick={viewModel.tags.value=if(tag.name in selectedTags) selectedTags-tag.name else selectedTags+tag.name},label={Text("${tag.name} (${tag.count})")})
                }
            }
            if(filtering) Text("筛选中…",style=MaterialTheme.typography.labelSmall)
            error?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            if(filtering && immediate != null) {
                androidx.compose.foundation.lazy.LazyRow {
                    items(immediate!!.size) { index -> val book=immediate!![index]
                        TextButton(onClick={selected=book}) { Text(book.title) }
                    }
                }
            }
            if (books.loadState.refresh is LoadState.Loading && books.itemCount == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (books.itemCount == 0) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error ?: if (query.isNotBlank() || category != "全部") "该分类下暂无书籍" else "书架还是空的，上传一本书开始阅读", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { books.retry(); viewModel.refresh() }) { Text("重试") }
                }
            } else LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(books.itemCount, key = books.itemKey { it.bookId }) { index ->
                    val book = books[index] ?: return@items
                    Column {
                        BookCell(book.title, book.author, book.coverUrl, authHeader, onClick = { selected = book }, placeholderChars = 2)
                        val progress = history.firstOrNull { it.bookId == book.bookId }?.progress
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(top = 5.dp)) {
                            Text(progress?.let { "已读 $it%" } ?: "未读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
                if (books.loadState.append is LoadState.Loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (books.loadState.append is LoadState.Error) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = { books.retry() }) { Text("加载失败，点击重试") } }
            }
        }
        ExtendedFloatingActionButton(onClick = { upload = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("上传图书") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    }
    if (upload) BookUploadSheet(onUploaded = { viewModel.refresh(); books.refresh() }, onDismiss = { upload = false })
    selected?.let { book ->
        BookDetailsSheet(book, authHeader, onRead = {
            selected = null; onSeedColorChange(book.seedColor)
            onOpenBook(book.bookId, book.title, book.author, book.coverUrl, book.epubHref)
        }, onDismiss = { selected = null }, onTag = { viewModel.tags.value=listOf(it); selected=null })
    }
}
