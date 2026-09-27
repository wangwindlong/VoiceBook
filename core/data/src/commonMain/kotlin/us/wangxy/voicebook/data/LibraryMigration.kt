package us.wangxy.voicebook.data

import us.wangxy.voicebook.reader.store.ReaderState
import us.wangxy.voicebook.reader.store.ReaderStateStore

/**
 * One-shot import of the pre-database persistence format: the reader state used to
 * be a single JSON blob (server config + history) written by [ReaderStateStore].
 * Server and history move into [LocalLibrary]; the reader font size stays in the
 * legacy store because the reader keeps using it. Re-running is harmless: migrated
 * fields are cleared from the blob and every write is an upsert.
 */
object LibraryMigration {
    suspend fun migrate(legacy: ReaderStateStore, library: LocalLibrary) {
        val state = legacy.load() ?: return

        state.server?.let { library.server.set(it) }
        // Oldest first so that, when both old and new data exist, the most recent
        // entry is written last and wins the upsert.
        state.history.reversed().forEach { library.history.upsert(it) }

        if (state.server != null || state.history.isNotEmpty()) {
            legacy.save(ReaderState(fontSizeSp = state.fontSizeSp))
        }
    }
}
