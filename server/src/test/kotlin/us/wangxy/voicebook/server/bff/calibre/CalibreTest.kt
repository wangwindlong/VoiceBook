package us.wangxy.voicebook.server.bff.calibre

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.bff.contract.ReadingProgressUpdate
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.createCalibreFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibreTest {
    private val config = createCalibreFixture()
    private val library = CalibreLibrary(config)
    private val progress = CwaProgressMirror(config)

    @Test
    fun optionalMetadataFiltersAndVersionCoverTagEdits() = runTest {
        val book=library.book(1)
        assertEquals("Science Press",book.publisher)
        assertEquals(listOf("zho"),book.languages)
        assertEquals("978:123",book.identifiers["isbn"])
        assertEquals(4.5,book.rating); assertEquals(302,book.pageCount); assertEquals("第二版",book.edition)
        assertNull(library.book(2).publisher); assertNull(library.book(2).rating)
        assertEquals(2L,library.books(null,0,10,tags=listOf("科幻","技术")).total)
        assertEquals(1L,library.books(null,0,10,tags=listOf("科幻","技术"),tagMode="all").total)
        assertEquals(listOf("Dune","三体"),library.books(null,0,10,sort="title").items.map { it.title })
        val before=library.books(null,0,10).libraryVersion
        withSqlite(config.metadataDb,false) { it.update("UPDATE tags SET name='技术改名' WHERE id=2") }
        kotlin.test.assertNotEquals(before,library.books(null,0,10).libraryVersion)
        assertEquals(listOf("三体"),library.books(null,0,10,tags=listOf("技术改名")).items.map { it.title })
    }

    @Test
    fun listsNewestFirstWithJoinedFields() = runTest {
        val page = library.books(null, 0, 10)
        assertEquals(2, page.total)
        assertEquals(listOf("Dune", "三体"), page.items.map { it.title })
        val threeBody = page.items[1]
        assertEquals(listOf("刘慈欣"), threeBody.authors)
        assertEquals("地球往事", threeBody.series)
        assertEquals(listOf("EPUB"), threeBody.formats)
        assertNull(threeBody.description)
    }

    @Test
    fun searchMatchesAuthorsAndEscapesWildcards() = runTest {
        assertEquals(listOf("三体"), library.books("慈欣", 0, 10).items.map { it.title })
        assertEquals(0, library.books("%", 0, 10).total)
    }

    @Test
    fun detailCoverAndFile() = runTest {
        assertEquals("<p>简介</p>", library.book(1).description)
        assertTrue(library.coverFile(1).isFile)
        assertEquals("三体 - 刘慈欣.epub", library.bookFile(1, "epub").name)
        assertFailsWith<BffException> { library.coverFile(2) }
        assertFailsWith<BffException> { library.book(99) }
    }

    @Test
    fun mirrorOnlyWritesPercentAndLeavesCfiUnchanged() = runTest {
        withSqlite(config.appDb,false) { c -> c.update("INSERT INTO bookmark(user_id,book_id,format,bookmark_key) VALUES(2,1,'EPUB','original-cfi')") }
        progress.write("alice",1,12.5)
        progress.write("alice",1,40.0)
        withSqlite(config.appDb,true) { c ->
            assertEquals("original-cfi",c.query("SELECT bookmark_key FROM bookmark") { it.getString(1) }.single())
            assertEquals(40.0,c.query("SELECT progress_percent FROM kobo_bookmark") { it.getDouble(1) }.single())
            assertEquals(1,c.query("SELECT count(*) FROM kobo_reading_state") { it.getInt(1) }.single())
        }
    }
    @Test
    fun missingUserAndUploadedBooksAreSkipped() = runTest {
        progress.write("bob",1,40.0)
        progress.write("alice",-1,40.0)
        withSqlite(config.appDb,true) { c -> assertEquals(0,c.query("SELECT count(*) FROM kobo_bookmark") { it.getInt(1) }.single()) }
    }
}
