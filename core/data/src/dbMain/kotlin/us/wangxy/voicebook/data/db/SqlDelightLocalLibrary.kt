package us.wangxy.voicebook.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
import us.wangxy.voicebook.db.BookCache
import us.wangxy.voicebook.db.ReadingHistory
import us.wangxy.voicebook.db.VoiceBookDatabase
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry

/** SQLDelight-backed [LocalLibrary] for android/ios/jvm. */
internal class SqlDelightLocalLibrary(private val db: VoiceBookDatabase) : LocalLibrary {

    private val rss = SqlDelightRssStores(db)
    private val accountStores = SqlDelightAccountStores(db)

    override val bookCache: BookCacheStore = SqlBookCacheStore(db)
    override val history: ReadingHistoryStore = SqlReadingHistoryStore(db)
    override val server: ServerStore = SqlServerStore(db)
    override val settings: SettingsStore = SqlSettingsStore(db)
    override val rssFeeds: RssFeedStore = rss.feeds
    override val rssPosts: RssPostStore = rss.posts
    override val rssAccount: RssAccountStore = rss.account
    override val calibreAccounts = accountStores.calibreAccounts
    override val rssSavedAccounts = accountStores.rssSavedAccounts
}

private class SqlBookCacheStore(private val db: VoiceBookDatabase) : BookCacheStore {

    override suspend fun upsertAll(books: List<CachedBook>, owner: String) = withContext(Dispatchers.IO) {
        db.bookCacheQueries.transaction {
            books.forEach { book ->
                db.bookCacheQueries.upsertBookCache(
                    bookId = book.bookId.toLong(),
                    title = book.title,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    epubHref = book.epubHref,
                    pos = book.pos.toLong(),
                    seedColor = book.seedColor?.toLong(), owner = owner,
                    tags = kotlinx.serialization.json.Json.encodeToString(book.tags), series = book.series,
                    publisher = book.publisher, rating = book.rating, pageCount = book.pageCount?.toLong(),
                    languages = kotlinx.serialization.json.Json.encodeToString(book.languages),
                    metadata = kotlinx.serialization.json.Json.encodeToString(CachedBook.serializer(),book),
                )
            }
        }
        Unit
    }

    override suspend fun page(limit: Int, offset: Int, owner: String): List<CachedBook> = withContext(Dispatchers.IO) {
        db.bookCacheQueries.selectBookCachePage(limit = limit.toLong(), offset = offset.toLong(), owner = owner)
            .executeAsList()
            .map { it.toCachedBook() }
    }

    override suspend fun count(owner: String): Int = withContext(Dispatchers.IO) {
        db.bookCacheQueries.countBookCache(owner).executeAsOne().toInt()
    }

    override suspend fun clear(owner: String) = withContext(Dispatchers.IO) {
        db.bookCacheQueries.clearBookCache(owner)
        Unit
    }

    override suspend fun updateSeedColor(bookId: Int, seedColor: Int?, owner: String) = withContext(Dispatchers.IO) {
        db.bookCacheQueries.updateBookCacheSeedColor(bookId = bookId.toLong(), seedColor = seedColor?.toLong(), owner = owner)
        Unit
    }

    private fun BookCache.toCachedBook(): CachedBook {
        val stored = runCatching { kotlinx.serialization.json.Json.decodeFromString<CachedBook>(metadata) }.getOrNull()
        return (stored ?: CachedBook(bookId.toInt(),title,author,coverUrl,epubHref,pos.toInt())).copy(seedColor=seedColor?.toInt())
    }

}

private class SqlReadingHistoryStore(private val db: VoiceBookDatabase) : ReadingHistoryStore {

    override fun observeAll(owner: String): Flow<List<HistoryEntry>> =
        db.historyQueries.selectAllHistory(owner)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEntry() } }

    override suspend fun upsert(entry: HistoryEntry, owner: String) = withContext(Dispatchers.IO) {
        db.historyQueries.upsertHistory(
            bookId = entry.bookId.toLong(),
            title = entry.title,
            author = entry.author,
            coverUrl = entry.coverUrl,
            format = entry.format,
            spineIndex = entry.spineIndex.toLong(),
            charOffset = entry.charOffset.toLong(),
            progress = entry.progress.toLong(),
            updatedAt = entry.updatedAt,
            seedColor = entry.seedColor?.toLong(), owner = owner, pending_sync = if (entry.pendingSync) 1L else 0L,
        )
        Unit
    }

    override suspend fun get(bookId: Int, owner: String): HistoryEntry? = withContext(Dispatchers.IO) {
        db.historyQueries.selectHistory(bookId = bookId.toLong(), owner = owner).executeAsOneOrNull()?.toEntry()
    }

    private fun ReadingHistory.toEntry() = HistoryEntry(
        bookId = bookId.toInt(),
        title = title,
        author = author,
        coverUrl = coverUrl,
        spineIndex = spineIndex.toInt(),
        charOffset = charOffset.toInt(),
        progress = progress.toInt(),
        updatedAt = updatedAt,
        format = format,
        pendingSync = pending_sync != 0L,
        seedColor = seedColor?.toInt(),
    )
}

private class SqlServerStore(private val db: VoiceBookDatabase) : ServerStore {

    override suspend fun get(): CalibreServer? = withContext(Dispatchers.IO) {
        db.serverConfigQueries.selectServerConfig(Id)
            .executeAsOneOrNull()
            ?.let { CalibreServer(it.baseUrl, it.username, it.password) }
    }

    override suspend fun set(server: CalibreServer?) = withContext(Dispatchers.IO) {
        if (server == null) {
            db.serverConfigQueries.deleteServerConfig(Id)
        } else {
            db.serverConfigQueries.upsertServerConfig(
                id = Id,
                baseUrl = server.baseUrl,
                username = server.username,
                password = server.password,
            )
        }
        Unit
    }

    private companion object {
        const val Id = 1L
    }
}

private class SqlSettingsStore(private val db: VoiceBookDatabase) : SettingsStore {

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        db.appSettingsQueries.upsertAppSetting(key, value)
        Unit
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        db.appSettingsQueries.selectAppSetting(key).executeAsOneOrNull()
    }
}
