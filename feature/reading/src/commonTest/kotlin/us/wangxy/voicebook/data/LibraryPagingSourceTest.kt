package us.wangxy.voicebook.data

import androidx.paging.PagingSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.reader.opds.OpdsFeed
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.reader.store.ReaderState
import us.wangxy.voicebook.reader.store.ReaderStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeReaderStateStore(var state: ReaderState? = null) : ReaderStateStore {
    override fun load(): ReaderState? = state
    override fun save(state: ReaderState) {
        this.state = state
    }
}

/** Serves `total` books in pages of 20, counting fetches; the client is never used. */
private open class FakeApi(private val total: Int) : CalibreWebApi(HttpClient()) {
    var fetchCount = 0

    override suspend fun newest(server: CalibreServer, offset: Int): OpdsFeed {
        fetchCount++
        val entries = (offset until minOf(offset + 20, total)).map { i ->
            OpdsEntry(
                bookId = i + 1,
                title = "Book $i",
                author = "Author",
                summary = "",
                coverHref = null,
                epubHref = "/opds/download/${i + 1}/EPUB/",
            )
        }
        val next = offset + 20
        return OpdsFeed(entries, next.takeIf { it < total })
    }

    override fun coverUrl(server: CalibreServer, entry: OpdsEntry): String = "cover/${entry.bookId}"
}

private suspend fun newRepository(api: CalibreWebApi): Pair<BookRepository, LocalLibrary> {
    val library = InMemoryLocalLibrary()
    library.server.set(CalibreServer("http://calibre.local"))
    val initializer = LibraryInitializer(FakeReaderStateStore(), library)
    return BookRepository(api, library, initializer) to library
}

class LibraryPagingSourceTest {

    @Test
    fun firstLoadFetchesAndCachesFirstPage() = runTest {
        val api = FakeApi(total = 45)
        val (repo, library) = newRepository(api)
        val source = LibraryPagingSource(repo, SHELF_PAGE_SIZE)

        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = SHELF_PAGE_SIZE, placeholdersEnabled = false))

        result as PagingSource.LoadResult.Page
        assertEquals(SHELF_PAGE_SIZE, result.data.size)
        assertEquals(1, api.fetchCount)
        assertEquals(SHELF_PAGE_SIZE, library.bookCache.count())
    }

    @Test
    fun cacheServesReloadWithoutNetwork() = runTest {
        val api = FakeApi(total = 45)
        val (repo, _) = newRepository(api)
        LibraryPagingSource(repo, SHELF_PAGE_SIZE)
            .load(PagingSource.LoadParams.Refresh(null, SHELF_PAGE_SIZE, false))

        api.fetchCount = 0
        val second = LibraryPagingSource(repo, SHELF_PAGE_SIZE)
        val result = second.load(PagingSource.LoadParams.Refresh(null, SHELF_PAGE_SIZE, false))

        result as PagingSource.LoadResult.Page
        assertEquals(SHELF_PAGE_SIZE, result.data.size)
        assertEquals(0, api.fetchCount, "second cold start must be served from cache")
    }

    @Test
    fun exhaustedShelfTerminatesPager() = runTest {
        val api = FakeApi(total = 45)
        val (repo, _) = newRepository(api)
        val source = LibraryPagingSource(repo, SHELF_PAGE_SIZE)
        // Paging loads pages sequentially; the cache's OFFSET assumes pages 0..n-1 exist.
        source.load(PagingSource.LoadParams.Append(0, SHELF_PAGE_SIZE, false))
        source.load(PagingSource.LoadParams.Append(1, SHELF_PAGE_SIZE, false))

        val last = source.load(PagingSource.LoadParams.Append(key = 2, loadSize = SHELF_PAGE_SIZE, placeholdersEnabled = false))
        last as PagingSource.LoadResult.Page
        assertEquals(5, last.data.size, "45 books, page index 2 → remaining 5")
        assertNull(last.nextKey, "short page past the total must end pagination")
    }

    @Test
    fun refreshClearsCacheAndRefetches() = runTest {
        val api = FakeApi(total = 45)
        val (repo, library) = newRepository(api)
        LibraryPagingSource(repo, SHELF_PAGE_SIZE)
            .load(PagingSource.LoadParams.Refresh(null, SHELF_PAGE_SIZE, false))

        repo.refresh()

        assertEquals(20, library.bookCache.count(), "refresh wipes then re-fetches page 0")
        assertEquals(2, api.fetchCount, "refresh always hits the network")
    }
}

class LibraryMigrationTest {

    @Test
    fun legacyServerAndHistoryMoveIntoLocalLibrary() = runTest {
        val legacyState = ReaderState(
            server = CalibreServer("http://legacy:8083", "user", "pass"),
            history = listOf(
                HistoryEntry(bookId = 7, title = "Old book", progress = 42, updatedAt = 100),
                HistoryEntry(bookId = 9, title = "Newer", progress = 10, updatedAt = 200),
            ),
            fontSizeSp = 21,
        )
        val legacy = FakeReaderStateStore(legacyState)
        val library = InMemoryLocalLibrary()
        val initializer = LibraryInitializer(legacy, library)

        initializer.awaitReady()

        assertEquals("http://legacy:8083", library.server.get()?.baseUrl)
        val history = library.history.observeAll().first()
        assertEquals(listOf(9, 7), history.map { it.bookId }, "history ordered by updatedAt desc")
        assertNull(legacy.state?.server, "moved fields are cleared from the legacy blob")
        assertTrue(legacy.state?.history?.isEmpty() == true)
        assertEquals(21, legacy.state?.fontSizeSp, "font size stays in the legacy store")
    }

    @Test
    fun migrationIsIdempotent() = runTest {
        val legacy = FakeReaderStateStore(ReaderState(server = CalibreServer("http://legacy:8083")))
        val library = InMemoryLocalLibrary()

        LibraryInitializer(legacy, library).awaitReady()
        LibraryMigration.migrate(legacy, library)

        assertNotNull(library.server.get())
        assertEquals(0, library.history.observeAll().first().size)
    }
}
