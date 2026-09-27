package us.wangxy.voicebook.reader.store

import kotlinx.browser.window

actual fun createReaderStateStore(): ReaderStateStore = WebReaderStateStore

/** localStorage with a graceful in-memory fallback when storage is unavailable (private mode). */
private object WebReaderStateStore : ReaderStateStore {
    private const val KEY = "voicebook.reader.state"
    private var memory: ReaderState? = null

    override fun load(): ReaderState? = try {
        window.localStorage.getItem(KEY)?.let(::decodeReaderState)
    } catch (_: Throwable) {
        memory
    }

    override fun save(state: ReaderState) {
        memory = state
        try {
            window.localStorage.setItem(KEY, state.encode())
        } catch (_: Throwable) {
            // Private-browsing mode or quota exceeded: keep the session value only.
        }
    }
}
