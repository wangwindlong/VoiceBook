package us.wangxy.voicebook.ui

import kotlinx.browser.window

actual fun createUiPrefsStore(): UiPrefsStore = WebUiPrefsStore

private object WebUiPrefsStore : UiPrefsStore {
    private var state = UiPrefsState()

    override fun load(): UiPrefsState {
        val history = runCatching { window.localStorage.getItem("voicebook.search_history") }.getOrNull()
        return state.copy(searchHistory = history?.let(::decodeSearchHistory) ?: state.searchHistory)
    }

    override fun save(state: UiPrefsState) {
        this.state = state
        runCatching { window.localStorage.setItem("voicebook.search_history", encodeSearchHistory(state.searchHistory)) }
    }
}
