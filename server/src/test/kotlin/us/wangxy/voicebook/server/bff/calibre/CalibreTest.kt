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
    private val progress = CalibreProgressStore(config)

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
    fun progressRoundTripAndUpsert() = runTest {
        progress.write("Alice", 1, ReadingProgressUpdate("epub", "epubcfi(/6/4)", 12.5))
        progress.write("alice", 1, ReadingProgressUpdate("EPUB", "epubcfi(/6/8)", 40.0))
        val read = progress.read("alice", 1)
        assertEquals("EPUB", read.format)
        assertEquals("epubcfi(/6/8)", read.position)
        assertEquals(40.0, read.percent)
    }

    @Test
    fun unknownUserIsConflict() = runTest {
        val e = assertFailsWith<BffException> { progress.read("bob", 1) }
        assertEquals(HttpStatusCode.Conflict, e.status)
        assertEquals(CalibreProgressStore.CALIBRE_USER_NOT_PROVISIONED, e.code)
    }
}
