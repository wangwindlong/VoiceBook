package us.wangxy.voicebook.data.theme

import androidx.compose.ui.graphics.toArgb
import com.materialkolor.ktx.themeColorOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.effectiveCalibreServer
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.screens.reader.decodeImageBitmap

/**
 * Extracts a Material theme seed color from an image: tiny fetch → platform image
 * decode → material-kolor dominant color. Results are cached per URL; concurrent
 * requests for the same URL share one extraction (Twine's SeedColorExtractor
 * pattern, rewritten on VoiceBook's decode seam). Serves both the calibre covers
 * (authenticated) and public RSS article images.
 */
class SeedColorExtractor(
    private val client: HttpClient,
    private val api: CalibreWebApi,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val session: BffSession? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val cacheMutex = Mutex()
    private val cache = HashMap<String, Int>()
    private val inFlight = mutableMapOf<String, Deferred<Int?>>()

    /** Calibre cover (may need Basic auth); also records the color for the cache entry. */
    suspend fun seedColorFor(coverUrl: String, server: CalibreServer): Int? =
        seedColor(coverUrl) { api.fetchCoverBytes(server, coverUrl) }

    /** Public image URL (RSS article/feed images); no auth. */
    suspend fun seedColorForUrl(imageUrl: String): Int? =
        seedColor(imageUrl) { client.get(imageUrl).bodyAsBytes() }

    private suspend fun seedColor(key: String, fetch: suspend () -> ByteArray): Int? {
        if (key.isEmpty()) return null
        cacheMutex.withLock { cache[key] }?.let { return it }

        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                scope.async { runCatching { extract(fetch) }.getOrNull() }
            }
        }
        return deferred.await()?.also { color ->
            cacheMutex.withLock {
                // Bounded cache; recomputing a dropped color is cheap, so no LRU bookkeeping.
                if (cache.size >= MaxCache) cache.clear()
                cache[key] = color
            }
            mutex.withLock { inFlight.remove(key) }
        }
    }

    /** Backfills seed colors for cached shelf entries that don't have one yet. */
    suspend fun backfill(limit: Int = BackfillLimit) {
        val server = library.effectiveCalibreServer(initializer, session) ?: return
        val missing = library.bookCache.page(limit, 0)
            .filter { it.seedColor == null && it.coverUrl.isNotEmpty() }
        for (book in missing) {
            val color = seedColorFor(book.coverUrl, server) ?: continue
            library.bookCache.updateSeedColor(book.bookId, color)
        }
    }

    private suspend fun extract(fetch: suspend () -> ByteArray): Int? =
        withContext(Dispatchers.Default) {
            val bitmap = decodeImageBitmap(fetch()) ?: return@withContext null
            bitmap.themeColorOrNull()?.toArgb()
        }

    private companion object {
        const val MaxCache = 500
        const val BackfillLimit = 24
    }
}
