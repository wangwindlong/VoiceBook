package us.wangxy.voicebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import us.wangxy.voicebook.ui.widget.GlassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.text.font.FontWeight
import androidx.paging.compose.collectAsLazyPagingItems
import us.wangxy.voicebook.ui.widget.ReferenceSearch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.rss.RssListQuery
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssPostsFilter
import us.wangxy.voicebook.rss.RssRepository
import us.wangxy.voicebook.screens.book.BookCover
import us.wangxy.voicebook.screens.shelf.ShelfViewModel
import us.wangxy.voicebook.screens.mine.MineScreen

@Composable
internal fun HomeScreen(
    pagerState: PagerState,
    contentPadding: PaddingValues,
    rssRepository: RssRepository,
    onOpenBook: (Int, String, String, String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenMine: () -> Unit,
    onOpenVoiceDebug: () -> Unit,
    onOpenMuseumDemo: () -> Unit,
    onOpenTwineDemo: () -> Unit,
    onOpenBloomDemo: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    onSidebarNavigate: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding)) {
        val sidebarWidth = minOf(maxWidth * 0.86f, SidebarWidth)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { it },
        ) { page ->
            when (page) {
                0 -> Box(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(16.dp)
                            .fillMaxHeight()
                            .width(sidebarWidth),
                    ) {
                        AppSidebarContent(onOpenMine = onOpenMine, onNavigate = onSidebarNavigate)
                    }
                }
                1 -> HomeContent(
                    rssRepository = rssRepository,
                    onOpenBook = onOpenBook,
                    onSeedColorChange = onSeedColorChange,
                    onOpenLibrary = onOpenLibrary,
                    onOpenRss = onOpenRss,
                    onOpenAi = onOpenAi,
                    onOpenPost = onOpenPost,
                    onOpenSidebar = onOpenSidebar,
                )
                2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth()) {
                        MineScreen(
                            onOpenVoiceDebug = onOpenVoiceDebug,
                            onOpenMuseumDemo = onOpenMuseumDemo,
                            onOpenSidebar = onOpenSidebar,
                            onOpenTwineDemo = onOpenTwineDemo,
                            onOpenBloomDemo = onOpenBloomDemo,
                            onOpenLogin = onOpenLogin,
                            onOpenChangePassword = onOpenChangePassword,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    rssRepository: RssRepository,
    onOpenBook: (Int, String, String, String) -> Unit,
    onSeedColorChange: (Int?) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenSidebar: () -> Unit,
) {
    val shelf = koinViewModel<ShelfViewModel>()
    val history by shelf.history.collectAsStateWithLifecycle()
    val server by shelf.server.collectAsStateWithLifecycle()
    val books = shelf.books.collectAsLazyPagingItems()
    val repository = org.koin.compose.koinInject<us.wangxy.voicebook.data.BookRepository>()
    var latestPosts by remember { mutableStateOf<List<RssPostModel>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchBooks by remember { mutableStateOf<List<us.wangxy.voicebook.data.CachedBook>>(emptyList()) }
    var searchPosts by remember { mutableStateOf<List<RssPostModel>>(emptyList()) }
    LaunchedEffect(query, server) {
        if (query.isNotBlank()) {
            kotlinx.coroutines.delay(250)
            val backend = server
            searchBooks = backend?.let { repository.search(it, query).mapIndexed { index, book ->
                    us.wangxy.voicebook.data.CachedBook(book.bookId, book.title, book.author,
                        if (it.viaBff) book.coverHref?.let { path -> it.root + path }.orEmpty() else book.coverHref?.let { path -> if (path.startsWith("http")) path else it.root + path }.orEmpty(), book.epubHref.orEmpty(), index)
                } }.orEmpty()
            try { searchPosts = rssRepository.postsPage(8, 0, RssListQuery(searchText = query)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchPosts = emptyList() }
        }
    }
    LaunchedEffect(rssRepository) {
        try { latestPosts = rssRepository.postsPage(40, 0, RssListQuery(filter = RssPostsFilter.All)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestPosts = emptyList() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("早上好，探索新知", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            androidx.compose.material3.IconButton(onClick = onOpenSidebar) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多功能", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Text("阅读 · 资讯 · AI · 工具", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                ReferenceSearch(query, { query = it }, "搜索书籍、新闻、问题…", glass = true)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShortcutTile("阅读", "书籍 · 书架", Icons.Filled.AutoStories, onOpenLibrary, Modifier.weight(1f))
                        ShortcutTile("资讯", "新闻 · 订阅", Icons.Filled.RssFeed, onOpenRss, Modifier.weight(1f))
                        ShortcutTile("AI助手", "对话 · 创意", Icons.Filled.SmartToy, onOpenAi, Modifier.weight(1f))
                    }
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (query.isBlank()) "今日推荐" else "书籍搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("更多 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenLibrary))
                            }
                            val recommended = (if (query.isBlank()) books.itemSnapshotList.items else searchBooks).take(4)
                            if (recommended.isEmpty()) {
                                Text(if (query.isBlank()) "书架里的好书将在这里推荐" else "暂无匹配书籍，进入书架搜索更多", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenLibrary).padding(vertical = 16.dp))
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    recommended.forEach { book ->
                                        Column(Modifier.weight(1f).clickable {
                                                onSeedColorChange(book.seedColor)
                                                onOpenBook(book.bookId, book.title, book.author, book.coverUrl)
                                        }) {
                                            BookCover(book.coverUrl, book.title, server?.let(shelf::coverAuthHeader), placeholderChars = 2)
                                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                                            Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (query.isBlank()) "最新资讯" else "资讯搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("更多 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenRss))
                            }
                            val filteredPosts = (if (query.isBlank()) latestPosts else searchPosts).take(if (query.isBlank()) 2 else 8)
                            if (filteredPosts.isEmpty()) Text("暂无资讯，添加订阅源后即可查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenRss).padding(vertical = 16.dp))
                            filteredPosts.forEach { post ->
                                Row(Modifier.fillMaxWidth().clickable { onOpenPost(post.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = post.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 60.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(post.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(post.feedTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                                    }
                                }
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun ShortcutTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier.height(108.dp),
        tint = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = when (title) { "阅读" -> Color(0xFF3478F6); "资讯" -> Color(0xFF4B9F61); "AI助手" -> Color(0xFF9161DF); else -> Color(0xFF6A8EC3) }, modifier = Modifier.size(30.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RecentBook(entry: HistoryEntry, authHeader: String?, onClick: () -> Unit) {
    Column(Modifier.width(66.dp).clickable(onClick = onClick)) {
        BookCover(
            coverUrl = entry.coverUrl,
            title = entry.title,
            authHeader = authHeader,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
            placeholderChars = 2,
        )
        Text(entry.title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF344754), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
