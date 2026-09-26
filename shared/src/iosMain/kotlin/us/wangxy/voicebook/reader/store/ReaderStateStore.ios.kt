package us.wangxy.voicebook.reader.store

import platform.Foundation.NSUserDefaults

actual fun createReaderStateStore(): ReaderStateStore = IosReaderStateStore

/**
 * One JSON string in standard user defaults — same storage pattern as the theme store;
 * the state is a few KB of JSON at most.
 */
private object IosReaderStateStore : ReaderStateStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): ReaderState? =
        defaults.stringForKey(KEY_STATE)?.let(::decodeReaderState)

    override fun save(state: ReaderState) {
        defaults.setObject(state.encode(), forKey = KEY_STATE)
    }

    private const val KEY_STATE = "reader_state_json"
}
