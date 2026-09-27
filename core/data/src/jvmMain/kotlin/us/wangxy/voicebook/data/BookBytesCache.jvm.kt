package us.wangxy.voicebook.data

import java.io.File

actual fun createBookBytesCache(): BookBytesCache = JvmBookBytesCache

internal actual fun fileNameFor(key: String): String =
    key.map { c -> if (c.isLetterOrDigit() || c == '.') c else '_' }.joinToString("") + ".bin"

/** ~/.voicebook/book_cache 目录下的字节文件。 */
private object JvmBookBytesCache : BookBytesCache {

    private val dir: File? by lazy {
        runCatching {
            File(System.getProperty("user.home"), ".voicebook/book_cache").apply { mkdirs() }
        }.getOrNull()
    }

    override suspend fun get(key: String): ByteArray? {
        val target = dir ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            target.resolve(fileNameFor(key)).takeIf { it.isFile }?.readBytes()
        }
    }

    override suspend fun put(key: String, bytes: ByteArray) {
        val target = dir ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { target.resolve(fileNameFor(key)).writeBytes(bytes) }
        }
    }
}
