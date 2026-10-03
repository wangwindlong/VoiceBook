package us.wangxy.voicebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import us.wangxy.voicebook.ui.widget.*
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
    onOpenGlobalComponentsDemo: () -> Unit,
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
            // 共 3 页且都常驻：避免滑动中途组装相邻页（侧边栏/我的）造成掉帧
            beyondViewportPageCount = 2,
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
                            onOpenGlobalComponentsDemo = onOpenGlobalComponentsDemo,
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
    val globalUi = LocalGlobalUi.current
    val clipboard = LocalClipboardManager.current
    val share = us.wangxy.voicebook.ui.rememberShareText()
    val reminder = rememberGlobalReminder()
    val shelf = koinViewModel<ShelfViewModel>()
    val history by shelf.history.collectAsStateWithLifecycle()
    val server by shelf.server.collectAsStateWithLifecycle()
    val books = shelf.books.collectAsLazyPagingItems()
    val repository = org.koin.compose.koinInject<us.wangxy.voicebook.data.BookRepository>()
    val personalization = org.koin.compose.koinInject<us.wangxy.voicebook.data.PersonalizationRepository>()
    val personalized by personalization.enabled.collectAsStateWithLifecycle()
    val owner by personalization.user.collectAsStateWithLifecycle()
    var recommendations by remember(owner) { mutableStateOf<List<us.wangxy.voicebook.data.CachedBook>>(emptyList()) }
    var showInterests by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(Unit) { personalization.load() }
    LaunchedEffect(owner, personalized) {
        recommendations=emptyList()
        if (owner != null && personalized) try {
            recommendations=personalization.recommendations()
            showInterests=personalization.needsOnboarding()
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
    }
    if(showInterests) us.wangxy.voicebook.screens.mine.InterestProfileSheet(onDismiss={showInterests=false},onboarding=true)
    var latestPosts by remember { mutableStateOf<List<RssPostModel>>(emptyList()) }
    LaunchedEffect(rssRepository) {
        try { latestPosts = rssRepository.postsPage(40, 0, RssListQuery(filter = RssPostsFilter.All)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestPosts = emptyList() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("早上好，探索新知", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                androidx.compose.material3.IconButton(onClick = onOpenSidebar) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多功能", tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            // TODO 副标题暂时隐藏，需要时恢复
                            // Text("阅读 · 资讯 · AI · 工具", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    GlassSurface(onClick = { globalUi.searchVisible = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("搜索书籍、资讯…", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShortcutTile("阅读", "书籍 · 书架", Icons.Filled.AutoStories, onOpenLibrary, Modifier.weight(1f))
                        ShortcutTile("资讯", "新闻 · 订阅", Icons.Filled.RssFeed, onOpenRss, Modifier.weight(1f))
                        ShortcutTile("AI助手", "对话 · 创意", Icons.Filled.SmartToy, onOpenAi, Modifier.weight(1f))
                    }
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("今日推荐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = shelf::refresh) { Text("同步进度") }
                                Text("更多 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenLibrary))
                            }
                            val recommended = (recommendations.takeIf { personalized && it.isNotEmpty() } ?: books.itemSnapshotList.items).take(4)
                            if (recommended.isEmpty()) {
                                Text("书架里的好书将在这里推荐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenLibrary).padding(vertical = 16.dp))
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    recommended.forEach { book ->
                                        Column(Modifier.weight(1f).combinedClickable(onLongClick = {
                                            globalUi.showMenu(listOf(
                                                ContextAction("打开") { onOpenBook(book.bookId, book.title, book.author, book.coverUrl) },
                                                ContextAction("复制书名") { clipboard.setText(AnnotatedString(book.title)); reminder("已复制书名", null, null) },
                                                ContextAction("分享") { share(book.title, book.coverUrl) },
                                            ))
                                        }, onClick = {
                                                onSeedColorChange(book.seedColor)
                                                onOpenBook(book.bookId, book.title, book.author, book.coverUrl)
                                        })) {
                                            BookCover(book.coverUrl, book.title, server?.let(shelf::coverAuthHeader), placeholderChars = 2)
                                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                                            Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val reading = history.firstOrNull { it.bookId == book.bookId }
                                            if (reading != null) {
                                                Text(
                                                    "已读 ${reading.progress.coerceIn(0, 100)}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                )
                                                us.wangxy.voicebook.ui.widget.LinearProgressIndicator(
                                                    progress = { reading.progress.coerceIn(0, 100) / 100f },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("最新资讯", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("更多 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenRss))
                            }
                            val filteredPosts = latestPosts.take(2)
                            if (filteredPosts.isEmpty()) Text("暂无资讯，添加订阅源后即可查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpenRss).padding(vertical = 16.dp))
                            filteredPosts.forEach { post ->
                                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenPost(post.id) }, onLongClick = {
                                    globalUi.showMenu(listOf(
                                        ContextAction("打开") { onOpenPost(post.id) },
                                        ContextAction("复制标题") { clipboard.setText(AnnotatedString(post.title)); reminder("已复制标题", null, null) },
                                        ContextAction("分享") { share(post.title, post.link) },
                                    ))
                                }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
