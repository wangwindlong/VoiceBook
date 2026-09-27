package us.wangxy.voicebook.reader.store

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readIntLe
import kotlinx.io.readString
import kotlinx.io.writeString

actual fun createReaderStateStore(): ReaderStateStore = JvmReaderStateStore

/** JSON file under ~/.voicebook/reader/, mirroring the sherpa models' home-directory convention. */
private object JvmReaderStateStore : ReaderStateStore {
    private val file: Path by lazy {
        val home = System.getProperty("user.home") ?: "."
        Path(Path(home, ".voicebook"), "reader/state.json")
    }

    override fun load(): ReaderState? = runCatching {
        SystemFileSystem.source(file).buffered().use { it.readString() }
    }.getOrNull()?.let(::decodeReaderState)

    override fun save(state: ReaderState) {
        runCatching {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(file).buffered().use { it.writeString(state.encode()) }
        }
    }
}
