package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry

/**
 * Pure in-memory [LocalLibrary]. Used as the web fallback when IndexedDB is not
 * reachable (private-mode browsers) and as the fake in tests.
 */
class InMemoryLocalLibrary : LocalLibrary {

    private val rss = InMemoryRssStores()

    override val bookCache: BookCacheStore = InMemoryBookCacheStore()
    override val history: ReadingHistoryStore = InMemoryHistoryStore()
    override val server: ServerStore = InMemoryServerStore()
    override val settings: SettingsStore = InMemorySettingsStore()
    override val rssFeeds: RssFeedStore = rss.feeds
    override val rssPosts: RssPostStore = rss.posts
    override val rssAccount: RssAccountStore = rss.account
    override val calibreAccounts: CalibreAccountStore = InMemoryCalibreAccounts()
    override val rssSavedAccounts: RssSavedAccountStore = rss.rssSavedAccounts
}

private class InMemoryCalibreAccounts : CalibreAccountStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<String, us.wangxy.voicebook.reader.api.CalibreServerAccount>()

    override suspend fun saved(): List<us.wangxy.voicebook.reader.api.CalibreServerAccount> =
        mutex.withLock { rows.values.sortedByDescending { it.lastUsedAt } }

    override suspend fun upsert(account: us.wangxy.voicebook.reader.api.CalibreServerAccount) = mutex.withLock {
        rows[account.id] = account
        Unit
    }

    override suspend fun delete(id: String) = mutex.withLock {
        rows.remove(id)
        Unit
    }
}

private class InMemoryBookCacheStore : BookCacheStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<Pair<String,Int>, CachedBook>()

    override suspend fun upsertAll(books: List<CachedBook>, owner: String) = mutex.withLock {
        books.forEach { rows[owner to it.bookId] = it }
        Unit
    }

    override suspend fun page(limit: Int, offset: Int, owner: String): List<CachedBook> = mutex.withLock {
        rows.filterKeys { it.first == owner }.values.sortedBy { it.pos }.drop(offset).take(limit)
    }

    override suspend fun count(owner: String): Int = mutex.withLock { rows.keys.count { it.first == owner } }

    override suspend fun clear(owner: String) = mutex.withLock {
        rows.keys.removeAll { it.first == owner }
        Unit
    }

    override suspend fun updateSeedColor(bookId: Int, seedColor: Int?, owner: String) = mutex.withLock {
        rows[owner to bookId]?.let { rows[owner to bookId] = it.copy(seedColor = seedColor) }
        Unit
    }
}

private class InMemoryHistoryStore : ReadingHistoryStore {
    private val mutex = Mutex()
    private val rows = MutableStateFlow<Map<Pair<String,Int>, HistoryEntry>>(emptyMap())

    override fun observeAll(owner: String): Flow<List<HistoryEntry>> =
        rows.asStateFlow().map { entries -> entries.filterKeys { it.first == owner }.values.sortedByDescending { it.updatedAt } }

    override suspend fun upsert(entry: HistoryEntry, owner: String) = mutex.withLock {
        rows.value = rows.value + ((owner to entry.bookId) to entry)
        Unit
    }

    override suspend fun get(bookId: Int, owner: String): HistoryEntry? = mutex.withLock { rows.value[owner to bookId] }
}

private class InMemoryServerStore : ServerStore {
    private val mutex = Mutex()
    private var current: CalibreServer? = null

    override suspend fun get(): CalibreServer? = mutex.withLock { current }

    override suspend fun set(server: CalibreServer?) = mutex.withLock {
        current = server
        Unit
    }
}

private class InMemorySettingsStore : SettingsStore {
    private val mutex = Mutex()
    private val values = mutableMapOf<String, String>()

    override suspend fun put(key: String, value: String) = mutex.withLock {
        values[key] = value
        Unit
    }

    override suspend fun get(key: String): String? = mutex.withLock { values[key] }
}
