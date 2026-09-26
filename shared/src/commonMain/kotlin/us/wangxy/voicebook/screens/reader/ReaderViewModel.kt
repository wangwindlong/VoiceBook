package us.wangxy.voicebook.screens.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.api.CalibreWebApiException
import us.wangxy.voicebook.reader.api.DownloadHttpException
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.pdf.PdfDocument
import us.wangxy.voicebook.reader.pdf.openPdfDocument
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.reader.txt.TxtBook
import kotlin.time.ExperimentalTime
import kotlin.time.Clock

sealed interface ReaderUiState {
    data object Idle : ReaderUiState
    data class Downloading(val received: Int, val total: Int) : ReaderUiState
    data class Failed(val message: String) : ReaderUiState
    data class Ready(val book: ReadableBook) : ReaderUiState
    data class ReadyPdf(val pdf: PdfDocument) : ReaderUiState
}

/**
 * Owns the downloaded book and the persisted reading position. Pagination and page
 * navigation live in the screen (they need viewport size + TextMeasurer); the VM only
 * persists (spineIndex, charOffset) anchors it receives — PDFs use spineIndex as the
 * page index.
 */
class ReaderViewModel(
    private val api: CalibreWebApi,
    private val stateController: ReaderStateController,
) : ViewModel() {

    private val uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val state: StateFlow<ReaderUiState> = uiState.asStateFlow()

    private var currentBookId = -1
    private var bookMeta: HistoryEntry? = null
    private var lastHref = ""

    fun downloadAndOpen(
        bookId: Int,
        title: String,
        author: String,
        coverUrl: String,
        downloadHref: String = "",
    ) {
        if (bookId == currentBookId &&
            (uiState.value is ReaderUiState.Ready || uiState.value is ReaderUiState.ReadyPdf)
        ) {
            return
        }
        currentBookId = bookId
        lastHref = downloadHref
        bookMeta = HistoryEntry(
            bookId = bookId,
            title = title,
            author = author,
            coverUrl = coverUrl,
        )
        val server: CalibreServer = stateController.state.value.server ?: run {
            uiState.value = ReaderUiState.Failed("未配置 calibre-web 服务器")
            return
        }
        val saved = stateController.historyFor(bookId)
        // Fresh clicks carry the feed's own download href (its last segment names the
        // format); history resumes fall back to the saved format.
        val format = api.formatFromHref(downloadHref)
            ?: saved?.format?.uppercase()?.takeIf { it in ReadableFormats }
            ?: "EPUB"
        if (format !in ReadableFormats) {
            uiState.value = ReaderUiState.Failed("暂不支持阅读 $format 格式（目前支持 EPUB / KEPUB / TXT / PDF）")
            return
        }
        uiState.value = ReaderUiState.Downloading(0, 0)
        viewModelScope.launch {
            try {
                val (bytes, actualFormat) = downloadWithFallback(server, bookId, downloadHref, format)
                val ready: ReaderUiState = withContext(Dispatchers.Default) {
                    when (actualFormat) {
                        "PDF" -> ReaderUiState.ReadyPdf(
                            openPdfDocument(bytes) ?: throw CalibreWebApiException("无法解析该 PDF 文件"),
                        )
                        "TXT" -> ReaderUiState.Ready(TxtBook.parse(bytes))
                        // EPUB and KEPUB share the same zip+OPF container.
                        else -> ReaderUiState.Ready(EpubBook.parse(bytes))
                    }
                }
                if (bookId != currentBookId) return@launch
                // PDFs resume by page (stored in spineIndex); EPUBs by chapter+offset.
                savedAnchor = saved?.let { it.spineIndex to if (actualFormat == "PDF") 0 else it.charOffset }
                bookMeta = bookMeta?.copy(format = actualFormat)
                uiState.value = ready
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (bookId == currentBookId) {
                    uiState.value = ReaderUiState.Failed(e.message ?: "打开失败")
                }
            }
        }
    }

    /**
     * Downloads the pinned format; when resuming from history (no feed href) a 404
     * retries the other readable formats, so old entries saved before a format was
     * supported and books re-uploaded in a different format still open.
     */
    private suspend fun downloadWithFallback(
        server: CalibreServer,
        bookId: Int,
        href: String,
        format: String,
    ): Pair<ByteArray, String> {
        val formats = if (href.isBlank()) {
            listOf(format.uppercase(), "PDF", "EPUB").distinct()
        } else {
            listOf(format.uppercase())
        }
        var lastCode = 0
        for (candidate in formats) {
            try {
                return api.downloadBook(server, bookId, href, candidate) to candidate
            } catch (e: DownloadHttpException) {
                lastCode = e.code
            }
        }
        throw CalibreWebApiException("下载失败 HTTP $lastCode（书库中没有该书的可读格式）")
    }

    /** Position restored from history after parsing (null when opening fresh). */
    var savedAnchor: Pair<Int, Int>? = null
        private set

    fun recordPosition(spineIndex: Int, charOffset: Int, progressPercent: Int) {
        val meta = bookMeta ?: return
        stateController.recordProgress(
            meta.copy(
                spineIndex = spineIndex,
                charOffset = charOffset,
                progress = progressPercent.coerceIn(0, 100),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    /** PDF progress: page index only. */
    fun recordPdfPosition(pageIndex: Int, pageCount: Int) {
        if (pageCount <= 0) return
        recordPosition(pageIndex, 0, pageIndex * 100 / pageCount)
    }

    fun retry() {
        val meta = bookMeta ?: return
        downloadAndOpen(meta.bookId, meta.title, meta.author, meta.coverUrl, lastHref)
    }

    private companion object {
        val ReadableFormats = setOf("EPUB", "KEPUB", "TXT", "PDF")
    }
}
