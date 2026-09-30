package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.Flow
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry

/** Local cache of the calibre-web shelf feed; the paging source reads through it. */
interface BookCacheStore {
    suspend fun upsertAll(books: List<CachedBook>, owner: String = "")
    suspend fun page(limit: Int, offset: Int, owner: String = ""): List<CachedBook>
    suspend fun count(owner: String = ""): Int
    suspend fun clear(owner: String = "")
    suspend fun updateSeedColor(bookId: Int, seedColor: Int?, owner: String = "")
}

/** Reading positions, ordered newest-first by [HistoryEntry.updatedAt]. */
interface ReadingHistoryStore {
    fun observeAll(owner: String = ""): Flow<List<HistoryEntry>>
    suspend fun upsert(entry: HistoryEntry, owner: String = "")
    suspend fun get(bookId: Int, owner: String = ""): HistoryEntry?
}

/** Single-row calibre-web connection config. */
interface ServerStore {
    suspend fun get(): CalibreServer?
    suspend fun set(server: CalibreServer?)
}

/** Tiny string key-value area (e.g. one-off migration markers). */
interface SettingsStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
}

/**
 * Every local persistence surface behind one object. SQLDelight on
 * android/ios/jvm (via [us.wangxy.voicebook.di.platformDatabaseModule] in the
 * dbMain source set), IndexedDB on js/wasmJs.
 */
interface LocalLibrary {
    val bookCache: BookCacheStore
    val history: ReadingHistoryStore
    val server: ServerStore
    val settings: SettingsStore
    val rssFeeds: RssFeedStore
    val rssPosts: RssPostStore
    val rssAccount: RssAccountStore
    val calibreAccounts: CalibreAccountStore
    val rssSavedAccounts: RssSavedAccountStore
}
