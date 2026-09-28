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
