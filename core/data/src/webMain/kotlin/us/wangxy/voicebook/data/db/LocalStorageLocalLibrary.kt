package us.wangxy.voicebook.data.db

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.data.BookCacheStore
import us.wangxy.voicebook.data.CalibreAccountStore
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.data.CachedBook
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.ReadingHistoryStore
import us.wangxy.voicebook.data.ServerStore
import us.wangxy.voicebook.data.SettingsStore
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry

/**
 * localStorage-backed [LocalLibrary] for js/wasmJs (SQLDelight has no web driver).
 * Each persistence area is one JSON document under its own key. The typed
 * Storage API works on both web targets; a private-mode or quota failure keeps
 * the session value in memory only, mirroring the other web stores.
 */
internal class LocalStorageLocalLibrary : LocalLibrary {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val historyFlows = mutableMapOf<String,MutableStateFlow<List<HistoryEntry>>>()
    private fun historyFlow(owner: String) = historyFlows.getOrPut(owner) { MutableStateFlow(readHistory(owner)) }
    private val memory = mutableMapOf<String, String>()

    private val rss = LocalStorageRssStores({ key -> readRaw(key) }, { key, value -> writeRaw(key, value) })

    override val bookCache: BookCacheStore = LocalBookCacheStore()
    override val history: ReadingHistoryStore = LocalHistoryStore()
    override val server: ServerStore = LocalServerStore()
    override val settings: SettingsStore = LocalSettingsStore()
    override val rssFeeds: RssFeedStore = rss.feeds
    override val rssPosts: RssPostStore = rss.posts
    override val rssAccount: RssAccountStore = rss.account
    override val calibreAccounts: CalibreAccountStore = LocalCalibreAccounts({ key -> readRaw(key) }, { key, value -> writeRaw(key, value) })
    override val rssSavedAccounts: RssSavedAccountStore = rss.rssSavedAccounts

    private fun readRaw(key: String): String? = try {
        window.localStorage.getItem(key)?.also { memory[key] = it }
    } catch (_: Throwable) {
        memory[key]
    }

    private fun writeRaw(key: String, value: String) {
        memory[key] = value
        try {
            window.localStorage.setItem(key, value)
        } catch (_: Throwable) {
            // Private-browsing mode or quota exceeded: keep the session value only.
        }
    }

    private fun readHistory(owner: String): List<HistoryEntry> {
        val raw=readRaw("$KEY_HISTORY:$owner") ?: (if(owner.isEmpty()) readRaw(KEY_HISTORY) else null) ?: return emptyList()
        return json.decodeFromString(ListSerializer(HistoryEntry.serializer()),raw).sortedByDescending { it.updatedAt }
    }

    private inner class LocalHistoryStore : ReadingHistoryStore {
        override fun observeAll(owner: String): Flow<List<HistoryEntry>> = historyFlow(owner).asStateFlow()

        override suspend fun upsert(entry: HistoryEntry, owner: String) {
            val current = historyFlow(owner).value.filterNot { it.bookId == entry.bookId }
            val next = (listOf(entry) + current).take(MaxHistory)
            writeRaw("$KEY_HISTORY:$owner", json.encodeToString(ListSerializer(HistoryEntry.serializer()), next))
            historyFlow(owner).value = next.sortedByDescending { it.updatedAt }
        }

        override suspend fun get(bookId: Int, owner: String): HistoryEntry? =
            historyFlow(owner).value.firstOrNull { it.bookId == bookId }
    }

    private inner class LocalBookCacheStore : BookCacheStore {

        private fun readCache(owner: String): List<CachedBook> =
            (readRaw("$KEY_CACHE:$owner") ?: (if(owner.isEmpty()) readRaw(KEY_CACHE) else null) ?: return emptyList())
                .let { json.decodeFromString(ListSerializer(CachedBook.serializer()), it) }

        override suspend fun upsertAll(books: List<CachedBook>, owner: String) {
            val merged = (readCache(owner).associateBy { it.bookId } + books.associateBy { it.bookId })
                .values.sortedBy { it.pos }
            writeRaw("$KEY_CACHE:$owner", json.encodeToString(ListSerializer(CachedBook.serializer()), merged))
        }

        override suspend fun page(limit: Int, offset: Int, owner: String): List<CachedBook> =
            readCache(owner).drop(offset).take(limit)

        override suspend fun count(owner: String): Int = readCache(owner).size

        override suspend fun clear(owner: String) {
            writeRaw("$KEY_CACHE:$owner", "[]")
        }

        override suspend fun updateSeedColor(bookId: Int, seedColor: Int?, owner: String) {
            val updated = readCache(owner).map { if (it.bookId == bookId) it.copy(seedColor = seedColor) else it }
            writeRaw("$KEY_CACHE:$owner", json.encodeToString(ListSerializer(CachedBook.serializer()), updated))
        }
    }

    private inner class LocalServerStore : ServerStore {

        override suspend fun get(): CalibreServer? {
            val raw = readRaw(KEY_SERVER) ?: return null
            return json.decodeFromString(CalibreServer.serializer(), raw)
        }

        override suspend fun set(server: CalibreServer?) {
            if (server == null) {
                memory.remove(KEY_SERVER)
                try {
                    window.localStorage.removeItem(KEY_SERVER)
                } catch (_: Throwable) {
                }
            } else {
                writeRaw(KEY_SERVER, json.encodeToString(CalibreServer.serializer(), server))
            }
        }
    }

    private inner class LocalSettingsStore : SettingsStore {

        override suspend fun put(key: String, value: String) {
            val next = readSettings() + (key to value)
            writeRaw(
                KEY_SETTINGS,
                json.encodeToString(MapSerializer(String.serializer(), String.serializer()), next),
            )
        }

        override suspend fun get(key: String): String? = readSettings()[key]

        private fun readSettings(): Map<String, String> {
            val raw = readRaw(KEY_SETTINGS) ?: return emptyMap()
            return json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }
    }

    private companion object {
        const val KEY_HISTORY = "voicebook.history"
        const val KEY_CACHE = "voicebook.bookCache"
        const val KEY_SERVER = "voicebook.server"
        const val KEY_SETTINGS = "voicebook.settings"
        const val MaxHistory = 50
    }
}
