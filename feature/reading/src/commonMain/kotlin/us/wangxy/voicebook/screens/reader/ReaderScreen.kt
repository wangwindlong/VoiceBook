package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.theme.LocalTwineTokens
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.VoiceBookTheme

/**
 * 阅读器：下载 EPUB → 按视口分页 → HorizontalPager 翻页。位置以 (章节, 字符偏移)
 * 锚点持久化，重进后从上次位置恢复；字号调整时用锚点重排并回到当前段落。
 */
@Composable
fun ReaderScreen(
    bookId: Int,
    title: String,
    author: String,
    coverUrl: String,
    downloadHref: String = "",
    navigateBack: () -> Unit,
) {
    val viewModel = koinViewModel<ReaderViewModel>()
    val stateController = koinInject<ReaderStateController>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 阅读页独立氛围色（书封 seed）：嵌套主题只作用于本页，纸面随书变色。
    val ambient = remember { SeedColorState() }
    val bookSeed by viewModel.bookSeedColor.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.downloadAndOpen(bookId, title, author, coverUrl, downloadHref)
    }

    // 评论区对 EPUB/TXT/PDF 所有阅读类型共用：宿主持控制器与抽屉，各 pager 只放入口。
    val comments = rememberReaderComments(bookId, title)
    val commentsState by comments.state.collectAsStateWithLifecycle()
    var showComments by remember { mutableStateOf(false) }
    LaunchedEffect(showComments) { if (showComments) comments.open() }

    LaunchedEffect(bookSeed) { ambient.update(bookSeed) }

    // 亮度/屏幕常亮(Android):进入应用设置,离开页面自动复位
    val prefs by stateController.state.collectAsStateWithLifecycle()
    ScreenBrightnessEffect(prefs.brightness, prefs.keepScreenOn)

    VoiceBookTheme(seedState = ambient) {
    val paper = LocalTwineTokens.current.paper
    // 纸色铺满全屏(含状态栏/导航栏),盖住主题渐变;内容避开系统栏。
    // 用 systemBars 而非 safeDrawing:排除刘海 cutout 的额外下移,标题紧贴状态栏下方。
    Box(Modifier.fillMaxSize().background(paper).windowInsetsPadding(WindowInsets.systemBars)) {
        when (val s = state) {
            ReaderUiState.Idle -> Unit

            is ReaderUiState.Downloading -> Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("正在下载《$title》")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            is ReaderUiState.Failed -> Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("打开失败：${s.message}", textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = viewModel::retry) { Text("重试") }
                    TextButton(onClick = navigateBack) { Text("返回") }
                }
            }

            is ReaderUiState.Ready -> BookPager(
                book = s.book,
                startAnchor = viewModel.savedAnchor,
                stateController = stateController,
                viewModel = viewModel,
                bookId = bookId,
                title = title,
                commentCount = commentsState.total,
                onOpenComments = { showComments = true },
                navigateBack = navigateBack,
            )

            is ReaderUiState.ReadyPdf -> PdfPager(
                pdf = s.pdf,
                startPage = viewModel.savedAnchor?.first ?: 0,
                stateController = stateController,
                viewModel = viewModel,
                title = title,
                commentCount = commentsState.total,
                onOpenComments = { showComments = true },
                navigateBack = navigateBack,
            )
        }

        if (showComments) {
            ReaderCommentsSheet(comments, onDismiss = { showComments = false })
        }
    }
    }
}
