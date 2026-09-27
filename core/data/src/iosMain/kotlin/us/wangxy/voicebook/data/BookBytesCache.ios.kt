package us.wangxy.voicebook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import platform.Foundation.NSHomeDirectory

actual fun createBookBytesCache(): BookBytesCache = IosBookBytesCache


/** 应用 Caches/book_cache 目录下的字节文件（系统空间不足可回收）。 */
private object IosBookBytesCache : BookBytesCache {

    private val dirPath: Path? by lazy {
        runCatching {
            Path(NSHomeDirectory() + "/Library/Caches/book_cache")
        }.getOrNull()
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.Default) {
        val dir = dirPath ?: return@withContext null
        val file = Path(dir, fileNameFor(key))
        runCatching {
            if (!SystemFileSystem.exists(file)) return@withContext null
            SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        }.getOrNull()
    }

    override suspend fun put(key: String, bytes: ByteArray): Unit = withContext(Dispatchers.Default) {
        val dir = dirPath ?: return@withContext
        runCatching {
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(Path(dir, fileNameFor(key))).buffered().use { it.write(bytes) }
        }
        Unit
    }
}
