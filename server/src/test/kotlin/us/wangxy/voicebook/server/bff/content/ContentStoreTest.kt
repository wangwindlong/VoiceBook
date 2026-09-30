package us.wangxy.voicebook.server.bff.content

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import us.wangxy.voicebook.bff.contract.ReadingProgressUpdate
import us.wangxy.voicebook.server.bff.BffException

class ContentStoreTest {
    private val root = Files.createTempDirectory("bff-content-test").toFile()
    private fun store() = ContentStore(File(root, "content.db"), File(root, "books"))

    @Test fun eventsDeduplicateAndMutedTagsStayMutedAcrossRebuilds() = runBlocking {
        val s=store()
        val event=us.wangxy.voicebook.bff.contract.BehaviorEvent("reading_session","book","1",ts="2026-09-01T00:00:00Z",seconds=60)
        s.setProgress("alice",1,ReadingProgressUpdate("EPUB","1:2",50.0))
        repeat(2) { s.record("alice",event,"client") }
        assertEquals(60,s.history("alice",50).items.single().totalSeconds)
        assertEquals(0,s.history("bob",50).total)
        s.mute("alice"," AI ")
        s.replaceProfile("alice",listOf(us.wangxy.voicebook.bff.contract.InterestTag("ai",1.0,"book_tag",5)),s.events("alice").last().id)
        assertEquals(0.0,s.profile("alice").tags.single().weight)
        assertTrue(s.profile("alice").tags.single().muted)
        assertTrue(s.profile("bob").tags.isEmpty())
        assertTrue(s.dirtyOwners().isEmpty())
    }
    @Test fun uploadedBooksArePrivateAndPersistent() = runBlocking {
        val book = store().add("alice", "../../notes.txt", "我的书", "作者", "文学", "第一章\n测试正文".toByteArray())
        assertTrue(book.id < 0)
        assertEquals(book, store().book("alice", book.id))
        assertEquals("第一章\n测试正文", store().file("alice", book.id, "TXT").readText())
        assertTrue(store().books("bob").isEmpty())
        assertFailsWith<BffException> { store().file("bob", book.id, "TXT") }
        assertEquals(listOf(book), store().books("alice", "我的"))
        assertTrue(store().books("alice", "不匹配").isEmpty())
    }
    @Test fun reactionsAreIdempotentAndCountUniqueAccounts() = runBlocking {
        assertEquals(1L, store().setReaction("alice", "entry-1", true).likes)
        assertEquals(1L, store().setReaction("alice", "entry-1", true).likes)
        assertFalse(store().reaction("bob", "entry-1").liked)
        assertEquals(2L, store().setReaction("bob", "entry-1", true).likes)
        assertEquals(1L, store().setReaction("alice", "entry-1", false).likes)
        assertEquals(1L, store().setReaction("alice", "entry-1", false).likes)
        assertEquals(0L, store().reaction("alice", "entry-2").likes)
    }
    @Test fun categoriesAndProgressArePerAccount() = runBlocking {
        store().setCategory("alice", "feed-1", "科技")
        assertEquals("科技", store().categories("alice").categories["feed-1"])
        assertTrue(store().categories("bob").categories.isEmpty())
        store().setProgress("alice", -1, ReadingProgressUpdate("TXT", "3:50", 32.0))
        assertEquals("3:50", store().progress("alice", -1).position)
        assertNull(store().progress("bob", -1).position)
        assertFailsWith<BffException> { store().setProgress("alice", -1, ReadingProgressUpdate("TXT", "0:0", Double.NaN)) }
    }
    @Test fun rejectsUnsupportedAndInvalidFiles() = runBlocking {
        assertFailsWith<BffException> { store().add("a", "test.exe", "t", "", "", byteArrayOf(1)) }
        assertFailsWith<BffException> { store().add("a", "test.pdf", "t", "", "", "HTML".toByteArray()) }
        assertFailsWith<BffException> { store().add("a", "test.epub", "t", "", "", "HTML".toByteArray()) }
        assertFailsWith<BffException> { store().add("a", "test.txt", "t", "", "", byteArrayOf()) }
    }
}
