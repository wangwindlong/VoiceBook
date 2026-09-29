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
import us.wangxy.voicebook.data.BookBytesCache
import us.wangxy.voicebook.data.ReaderSessionRepository
import us.wangxy.voicebook.data.theme.SeedColorExtractor
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
    /** Kept for the reader font size; server config and history live in the session repository. */
    private val stateController: ReaderStateController,
    private val repository: ReaderSessionRepository,
    private val seedExtractor: SeedColorExtractor,
    private val bytesCache: BookBytesCache,
) : ViewModel() {

    /** Accent color of the current book's cover; drives the reader ambient theme. */
    private val bookSeedFlow = MutableStateFlow<Int?>(null)
    val bookSeedColor: StateFlow<Int?> = bookSeedFlow.asStateFlow()

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
        uiState.value = ReaderUiState.Downloading(0, 0)
        viewModelScope.launch {
            try {
                val server: CalibreServer = repository.server() ?: run {
                    uiState.value = ReaderUiState.Failed("未配置 calibre-web 服务器")
                    return@launch
                }
                val saved = repository.historyFor(bookId)
                // Fresh clicks carry the feed's own download href (its last segment names the
                // format); history resumes fall back to the saved format.
                val format = api.formatFromHref(downloadHref)
                    ?: saved?.format?.uppercase()?.takeIf { it in ReadableFormats }
                    ?: "EPUB"
                if (format !in ReadableFormats) {
                    uiState.value = ReaderUiState.Failed("暂不支持阅读 $format 格式（目前支持 EPUB / KEPUB / TXT / PDF）")
                    return@launch
                }
                var (bytes, actualFormat) = cachedOrDownload(server, bookId, downloadHref, format)
                val ready: ReaderUiState = try {
                    parseBook(bytes, actualFormat)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 缓存内容损坏(如历史上缓存过服务器错误页)时清掉重下一次
                    bytesCache.evict("${server.baseUrl}|$bookId|$actualFormat")
                    val (fresh, freshFormat) = downloadWithFallback(server, bookId, downloadHref, format)
                    bytesCache.put("${server.baseUrl}|$bookId|$freshFormat", fresh)
                    actualFormat = freshFormat
                    parseBook(fresh, freshFormat)
                }
                if (bookId != currentBookId) return@launch
                // PDFs resume by page (stored in spineIndex); EPUBs by chapter+offset.
                savedAnchor = saved?.let { it.spineIndex to if (actualFormat == "PDF") 0 else it.charOffset }
                bookMeta = bookMeta?.copy(format = actualFormat)
                uiState.value = ready
                publishBookSeed(bookId, saved?.seedColor)
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
     * 本地缓存命中则免下载直接打开；未命中下载后按「baseUrl|bookId|格式」写入缓存。
     * 键含 baseUrl，换服务器自动失效；续读时的 404 格式回退可能得到与请求不同的
     * actualFormat，缓存只按 actualFormat 存（历史条目随后更新为该格式，下次即命中）。
     * 疑似 HTML 的响应（登录页/错误页）不入缓存并直接报错。
     */
    private suspend fun cachedOrDownload(
        server: CalibreServer,
        bookId: Int,
        href: String,
        format: String,
    ): Pair<ByteArray, String> {
        bytesCache.get("${server.baseUrl}|$bookId|$format")?.let { return it to format }
        return downloadWithFallback(server, bookId, href, format).also { (bytes, actualFormat) ->
            if (looksLikeHtml(bytes)) {
                throw CalibreWebApiException("书库返回的不是书籍文件（可能是登录页或错误页），请检查服务器登录状态")
            }
            bytesCache.put("${server.baseUrl}|$bookId|$actualFormat", bytes)
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

    /** 按格式把字节解析为可读状态（EPUB/KEPUB 共用 zip+OPF 容器）。 */
    private suspend fun parseBook(bytes: ByteArray, actualFormat: String): ReaderUiState =
        withContext(Dispatchers.Default) {
            when (actualFormat) {
                "PDF" -> ReaderUiState.ReadyPdf(
                    openPdfDocument(bytes) ?: throw CalibreWebApiException("无法解析该 PDF 文件"),
                )
                "TXT" -> ReaderUiState.Ready(TxtBook.parse(bytes))
                else -> ReaderUiState.Ready(EpubBook.parse(bytes))
            }
        }

    /** HTML/错误页首字节以 '<' 开头;EPUB 是 zip(PK)、PDF 是 %PDF、TXT 都不会。 */
    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val head = bytes.decodeToString(0, minOf(bytes.size, 64), throwOnInvalidSequence = false)
        return head.trimStart().startsWith("<")
    }

    /** Position restored from history after parsing (null when opening fresh). */
    var savedAnchor: Pair<Int, Int>? = null
        private set

    /**
     * 发布书的封面氛围色；历史里没有就现场从封面提取并持久化，
     * 这样读书页的纸面颜色在旧书目上也能生效。
     */
    private fun publishBookSeed(bookId: Int, known: Int?) {
        bookSeedFlow.value = known
        val cover = bookMeta?.coverUrl
        if (known == null && !cover.isNullOrBlank()) {
            viewModelScope.launch {
                val server = repository.server() ?: return@launch
                val color = runCatching { seedExtractor.seedColorFor(cover, server) }.getOrNull() ?: return@launch
                if (bookId == currentBookId) {
                    bookSeedFlow.value = color
                    repository.updateHistorySeed(bookId, color)
                }
            }
        }
    }

    /** History entry of the open book (with its resolved format); null until a book is opened. */
    fun historyEntry(): HistoryEntry? = bookMeta

    fun recordPosition(spineIndex: Int, charOffset: Int, progressPercent: Int) {
        val meta = bookMeta ?: return
        viewModelScope.launch {
            repository.recordProgress(
                meta.copy(
                    spineIndex = spineIndex,
                    charOffset = charOffset,
                    progress = progressPercent.coerceIn(0, 100),
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
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
