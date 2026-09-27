package us.wangxy.voicebook

import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.reader.store.ReaderState
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.reader.store.decodeReaderState
import us.wangxy.voicebook.reader.store.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderStateStoreTest {

    private class MemStore : us.wangxy.voicebook.reader.store.ReaderStateStore {
        var saved: ReaderState? = null
        override fun load(): ReaderState? = saved
        override fun save(state: ReaderState) {
            saved = state
        }
    }

    @Test
    fun jsonRoundTripPreservesServerAndHistory() {
        val state = ReaderState(
            server = CalibreServer("http://192.168.1.5:8083", "alice", "pass中"),
            history = listOf(
                HistoryEntry(1, "三体", "刘慈欣", "http://x/cover/1", 3, 1024, 45, 1L),
                HistoryEntry(2, "The Book", "Author", "", 0, -1, 0, 2L),
            ),
            fontSizeSp = 20,
        )
        val decoded = decodeReaderState(state.encode())
        assertEquals(state, decoded)
    }

    @Test
    fun decodeOfGarbageReturnsNull() {
        assertNull(decodeReaderState("not json"))
        assertNull(decodeReaderState(""))
    }

    @Test
    fun controllerUpsertsAndReordersHistory() {
        val store = MemStore()
        val controller = ReaderStateController(store)
        controller.recordProgress(HistoryEntry(1, "书一", progress = 10, updatedAt = 1))
        controller.recordProgress(HistoryEntry(2, "书二", progress = 30, updatedAt = 2))
        controller.recordProgress(HistoryEntry(1, "书一", progress = 60, updatedAt = 3))

        val history = controller.state.value.history
        assertEquals(listOf(1, 2), history.map { it.bookId })
        assertEquals(60, history[0].progress)
        // Persisted on every mutation.
        assertEquals(controller.state.value, store.saved)
    }

    @Test
    fun historyCapKeepsMostRecentFifty() {
        val controller = ReaderStateController(MemStore())
        repeat(60) { i -> controller.recordProgress(HistoryEntry(i, "书$i")) }
        val ids = controller.state.value.history.map { it.bookId }
        assertEquals(50, ids.size)
        assertFalse(ids.contains(0))
        assertTrue(ids.contains(59))
    }

    @Test
    fun fontSizeClamped() {
        val controller = ReaderStateController(MemStore())
        controller.setFontSize(5)
        assertEquals(12, controller.state.value.fontSizeSp)
        controller.setFontSize(99)
        assertEquals(32, controller.state.value.fontSizeSp)
    }

    @Test
    fun historyForFindsById() = runTest {
        val controller = ReaderStateController(MemStore())
        controller.recordProgress(HistoryEntry(9, "书九"))
        assertEquals("书九", controller.historyFor(9)?.title)
        assertNull(controller.historyFor(8))
    }
}
