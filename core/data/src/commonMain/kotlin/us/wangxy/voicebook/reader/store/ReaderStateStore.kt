package us.wangxy.voicebook.reader.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.reader.api.CalibreServer

/** Platform file storage: load returns null when nothing saved yet. */
interface ReaderStateStore {
    fun load(): ReaderState?
    fun save(state: ReaderState)
}

/**
 * Single source of truth for server config + reading history. Mutators update the flow
 * and persist immediately; reads are cold-start only (load happens in the singleton's
 * init), keeping the store simple and synchronous like [us.wangxy.voicebook.theme.ThemeController].
 */
class ReaderStateController(private val store: ReaderStateStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val stateFlow = MutableStateFlow(store.load() ?: ReaderState())
    val state: StateFlow<ReaderState> = stateFlow.asStateFlow()

    fun setServer(server: CalibreServer?) = update { it.copy(server = server) }

    fun setFontSize(sizeSp: Int) = update { it.copy(fontSizeSp = sizeSp.coerceIn(12, 32)) }

    fun setLineHeight(factor: Float) = update { it.copy(lineHeightFactor = factor.coerceIn(1.2f, 2.6f)) }

    fun setPageMargin(marginDp: Int) = update { it.copy(pageMarginDp = marginDp.coerceIn(8, 56)) }

    fun setPageTurn(pageTurn: ReaderPageTurn) = update { it.copy(pageTurn = pageTurn) }

    fun setFontFamily(fontFamily: ReaderFontFamily) = update { it.copy(fontFamily = fontFamily) }

    fun setFirstLineIndent(indent: Boolean) = update { it.copy(firstLineIndent = indent) }

    fun setBrightness(brightness: Float) = update {
        it.copy(brightness = if (brightness < 0f) -1f else brightness.coerceIn(0.05f, 1f))
    }

    fun setKeepScreenOn(keepScreenOn: Boolean) = update { it.copy(keepScreenOn = keepScreenOn) }

    fun setAutoRead(millis: Long) = update { it.copy(autoReadMillis = millis.coerceIn(0, 60_000)) }

    fun setPdfPageFit(fit: PdfPageFit) = update { it.copy(pdfPageFit = fit) }

    /** Upserts the book's position and moves it to the front of the history list. */
    fun recordProgress(entry: HistoryEntry) {
        update { current ->
            val others = current.history.filterNot { it.bookId == entry.bookId }
            current.copy(history = (listOf(entry) + others).take(MaxHistory))
        }
    }

    fun historyFor(bookId: Int): HistoryEntry? =
        stateFlow.value.history.firstOrNull { it.bookId == bookId }

    private fun update(transform: (ReaderState) -> ReaderState) {
        val next = transform(stateFlow.value)
        stateFlow.value = next
        store.save(next)
    }

    private companion object {
        const val MaxHistory = 50
    }
}

/** JSON wire format used by all platform stores. */
internal fun ReaderState.encode(): String = Json.encodeToString(this)

internal fun decodeReaderState(text: String): ReaderState? = runCatching {
    Json.decodeFromString<ReaderState>(text)
}.getOrNull()
