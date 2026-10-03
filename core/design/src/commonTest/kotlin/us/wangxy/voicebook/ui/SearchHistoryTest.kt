package us.wangxy.voicebook.ui

import kotlin.test.*

class SearchHistoryTest {
    private class Store : UiPrefsStore {
        var value = UiPrefsState()
        override fun load() = value
        override fun save(state: UiPrefsState) { value = state }
    }
    @Test fun historyDeduplicatesCapsAndPersists() {
        val store = Store()
        val controller = UiPrefsController(store)
        repeat(15) { controller.recordSearch("词$it") }
        controller.recordSearch("  词5  ")
        controller.recordSearch("   ")
        assertEquals(12, controller.current.searchHistory.size)
        assertEquals("词5", controller.current.searchHistory.first())
        assertEquals(1, controller.current.searchHistory.count { it == "词5" })
        assertEquals(controller.current.searchHistory, UiPrefsController(store).current.searchHistory)
        controller.clearSearchHistory()
        assertTrue(store.value.searchHistory.isEmpty())
    }
    @Test fun historyHandlesPunctuationAndCorruptStorage() {
        val items = listOf("书名,含逗号", "换行\n内容", "\"引号\"")
        assertEquals(items, decodeSearchHistory(encodeSearchHistory(items)))
        assertTrue(decodeSearchHistory("broken").isEmpty())
    }
}
