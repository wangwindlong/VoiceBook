package us.wangxy.voicebook.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.File

actual fun createBookBytesCache(): BookBytesCache {
    val context = KoinPlatform.getKoin().get<Context>()
    return AndroidBookBytesCache(context.applicationContext)
}

/** 应用私有 filesDir/book_cache 下的字节文件。 */
private class AndroidBookBytesCache(context: Context) : BookBytesCache {
    private val dir = File(context.filesDir, "book_cache").apply { mkdirs() }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = dir.resolve(normalizeFileName(key))
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    override suspend fun put(key: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        runCatching { dir.resolve(normalizeFileName(key)).writeBytes(bytes) }
        Unit
    }
}

internal actual fun fileNameFor(key: String): String = normalizeFileName(key)

private fun normalizeFileName(key: String): String =
    key.map { c -> if (c.isLetterOrDigit() || c == '.') c else '_' }.joinToString("") + ".bin"
