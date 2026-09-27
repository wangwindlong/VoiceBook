@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.VoiceBookTheme
import us.wangxy.voicebook.audio.AudioPlayer
import us.wangxy.voicebook.audio.AudioTrack
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.HtmlParser
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssRepository

/** 文章阅读页：进入即已读 + 氛围取色；音频条目带内嵌播放器。 */
@Composable
fun ArticleScreen(
    postId: String,
    onBack: () -> Unit,
    repository: RssRepository = koinInject(),
    audioPlayer: AudioPlayer = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var post by remember { mutableStateOf<RssPostModel?>(null) }
    var starred by remember { mutableStateOf(false) }
    val audioState by audioPlayer.state.collectAsStateWithLifecycle()
    // 阅读页独立氛围色（Twine ReaderScreen 的 articleDynamicColorState 模式）：
    // 本地 animator 只驱动本页的嵌套主题，离开即失效，不污染全局。
    val ambient = remember { SeedColorState() }

    LaunchedEffect(postId) {
        val loaded = repository.postById(postId)
        if (loaded == null) {
            onBack()
            return@LaunchedEffect
        }
        post = loaded
        starred = loaded.starred
        if (!loaded.read) repository.markRead(listOf(loaded.id), true)
    }

    // Leaving the screen persists audio progress for this post.
    DisposableEffect(postId) {
        onDispose {
            val state = audioPlayer.state.value
            if (state.track?.postId == postId && state.positionMs > 0) {
                scope.launch { repository.setAudioProgress(postId, state.positionMs, state.durationMs) }
            }
        }
    }

    // 嵌套主题：文章氛围色只作用于本页（含阅读器自己的渐变背景）。
    VoiceBookTheme(seedState = ambient) {
        ArticleContent(
            post = post,
            starred = starred,
            audioState = audioState,
            audioPlayer = audioPlayer,
            onBack = onBack,
            onToggleStar = {
                val current = post ?: return@ArticleContent
                starred = !starred
                scope.launch { repository.setStarred(current.id, starred) }
            },
            onSeedColorChange = { ambient.update(it) },
        )
    }
}

@Composable
private fun ArticleContent(
    post: RssPostModel?,
    starred: Boolean,
    audioState: us.wangxy.voicebook.audio.AudioState,
    audioPlayer: AudioPlayer,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onSeedColorChange: (Int?) -> Unit,
) {
    LaunchedEffect(post?.seedColor) { onSeedColorChange(post?.seedColor) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleStar) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "星标",
                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val current = post
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${current.feedTitle} · ${relativeTime(current.publishedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current.imageUrl != null) {
                AsyncImage(
                    model = current.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            if (current.hasAudio) {
                InlineAudioBar(
                    title = current.title,
                    source = current.feedTitle,
                    player = audioPlayer,
                    state = audioState,
                    onStart = {
                        audioPlayer.load(
                            AudioTrack(
                                url = current.audioUrl.orEmpty(),
                                title = current.title,
                                source = current.feedTitle,
                                imageUrl = current.imageUrl,
                                postId = current.id,
                            ),
                            current.audioPositionMs,
                        )
                    },
                )
            }
            ArticleBlocks(html = current.contentHtml.ifBlank { current.summary })
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 正文渲染：复用 EPUB 章节解析（标题层级 / 引用 / 列表 / 图片）。 */
@Composable
private fun ArticleBlocks(html: String) {
    val blocks = remember(html) { HtmlParser.parse(html) }
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp)
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val text = buildAnnotatedString {
                        for (span in block.spans) {
                            pushStyle(
                                SpanStyle(
                                    fontWeight = if (span.bold) FontWeight.Bold else null,
                                    fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                                ),
                            )
                            append(span.text)
                            pop()
                        }
                    }
                    when {
                        block.headingLevel in 1..3 -> Text(
                            text = text,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = when (block.headingLevel) {
                                    1 -> 22.sp
                                    2 -> 19.sp
                                    else -> 17.sp
                                },
                            ),
                            fontWeight = FontWeight.Bold,
                        )

                        block.quote -> Column {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(4.dp)
                                    .background(Color.Transparent),
                            )
                            Text(text, style = body.copy(color = quoteColor))
                        }

                        else -> SelectionContainer { Text(text, style = body) }
                    }
                }

                is Block.Image -> AsyncImage(
                    model = block.src,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )

                is Block.Ruler -> HorizontalDivider()
            }
        }
    }
}

/** 内嵌音频条：进度条 + 播放/暂停 + 续播。 */
@Composable
private fun InlineAudioBar(
    title: String,
    source: String,
    player: AudioPlayer,
    state: us.wangxy.voicebook.audio.AudioState,
    onStart: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = {
                    if (state.track == null) onStart() else player.toggle()
                }) {
                    Icon(
                        if (state.playing) Icons.Filled.PlayArrow else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.durationMs > 0) {
                Slider(
                    value = state.positionMs.toFloat() / state.durationMs,
                    onValueChange = { player.seekTo(it) },
                )
            }
        }
    }
}
