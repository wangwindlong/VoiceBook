@file:OptIn(kotlin.time.ExperimentalTime::class)

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
    private val content: us.wangxy.voicebook.bff.ContentApi? = null,
) {

    /** 生效的书库（已登录统一账号时为 BFF），与 [BookRepository.server] 一致。 */
    suspend fun server(): CalibreServer? = library.effectiveCalibreServer(initializer, session)

    suspend fun saveServer(server: CalibreServer?) {
        initializer.awaitReady()
        library.server.set(server)
    }

    suspend fun historyFor(bookId: Int): HistoryEntry? {
        initializer.awaitReady()
        val local = library.history.get(bookId)
        if (session?.signedInUser?.value != null && content != null) {
            try {
                val remote = content.progress(bookId.toLong())
                val updated = remote.updatedAt?.let { kotlin.time.Instant.parse(it).toEpochMilliseconds() } ?: 0
                if (updated > (local?.updatedAt ?: 0)) {
                    val parts = remote.position?.split(':')
                    val chapter = parts?.getOrNull(0)?.toIntOrNull()
                    val offset = parts?.getOrNull(1)?.toIntOrNull()
                    if (chapter != null && offset != null) return (local ?: HistoryEntry(bookId, "")).copy(
                        spineIndex = chapter, charOffset = offset, format = remote.format ?: "EPUB",
                        progress = remote.percent?.toInt() ?: 0, updatedAt = updated)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Reading remains available offline. */ }
        }
        return local
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
        if (session?.signedInUser?.value != null && content != null) {
            try { content.setProgress(entry.bookId.toLong(), us.wangxy.voicebook.bff.contract.ReadingProgressUpdate(
                entry.format, "${entry.spineIndex}:${entry.charOffset}", entry.progress.toDouble())) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Local progress is already saved. */ }
        }
    }
}
