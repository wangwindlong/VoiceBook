package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry

/**
 * Reading-session state (server config + per-book positions), split out of the
 * shelf repository so feature:reader and feature:library can share it without a
 * feature-to-feature dependency.
 */
class ReaderSessionRepository(
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val session: BffSession? = null,
) {

    /** 生效的书库（已登录统一账号时为 BFF），与 [BookRepository.server] 一致。 */
    suspend fun server(): CalibreServer? = library.effectiveCalibreServer(initializer, session)

    suspend fun saveServer(server: CalibreServer?) {
        initializer.awaitReady()
        library.server.set(server)
    }

    suspend fun historyFor(bookId: Int): HistoryEntry? {
        initializer.awaitReady()
        return library.history.get(bookId)
    }

    suspend fun history(): List<HistoryEntry> {
        initializer.awaitReady()
        return library.history.observeAll().first()
    }

    fun observeHistory(): Flow<List<HistoryEntry>> = library.history.observeAll()

    /** Persists an accent color extracted from the book cover (dynamic theme). */
    suspend fun updateHistorySeed(bookId: Int, color: Int) {
        initializer.awaitReady()
        library.history.get(bookId)?.let { library.history.upsert(it.copy(seedColor = color)) }
    }

    suspend fun recordProgress(entry: HistoryEntry) {
        initializer.awaitReady()
        // Preserve any accent color already extracted for this book.
        val previous = library.history.get(entry.bookId)
        library.history.upsert(entry.copy(seedColor = entry.seedColor ?: previous?.seedColor))
    }
}
