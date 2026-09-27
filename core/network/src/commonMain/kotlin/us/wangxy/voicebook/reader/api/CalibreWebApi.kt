package us.wangxy.voicebook.reader.api

import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLPathPart
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.reader.opds.OpdsFeed
import us.wangxy.voicebook.reader.opds.OpdsParser
import kotlin.io.encoding.Base64

open class CalibreWebApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Download reached the server but it refused (404 = book has no such format). */
class DownloadHttpException(val code: Int, message: String) : CalibreWebApiException(message)

/**
 * Thin client for the calibre-web OPDS catalog: newest listing with offset pagination,
 * search, cover URLs and EPUB downloads. Uses the shared Koin [HttpClient]; the default
 * engine is wired per platform (OkHttp/Darwin/Js).
 */
open class CalibreWebApi(private val client: HttpClient) {

    open suspend fun newest(server: CalibreServer, offset: Int = 0): OpdsFeed =
        feed(server, "opds/new", offset)

    open suspend fun search(server: CalibreServer, query: String, offset: Int = 0): OpdsFeed =
        feed(server, "opds/search/" + encodePath(query), offset)

    /**
     * Downloads the book file for reading. Prefers the OPDS feed's own acquisition
     * [href] (its last path segment names the format); falls back to the
     * /opds/download/{bookId}/{FORMAT}/ route. 404 means the book has no such format.
     */
    suspend fun downloadBook(server: CalibreServer, bookId: Int, href: String?, format: String): ByteArray {
        val url = if (href.isNullOrBlank()) {
            absolute(server, "/opds/download/$bookId/${format.uppercase()}/")
        } else {
            absolute(server, href)
        }
        return try {
            val response = client.get(url) {
                if (server.username.isNotEmpty()) basicAuth(server.username, server.password)
            }
            if (!response.status.isSuccess()) {
                throw DownloadHttpException(response.status.value, "下载失败 HTTP ${response.status.value}")
            }
            response.bodyAsBytes()
        } catch (e: CalibreWebApiException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CalibreWebApiException("下载失败: ${e.message}", e)
        }
    }

    /** Format segment of an OPDS download href (/opds/download/123/PDF/ → PDF), null when absent. */
    fun formatFromHref(href: String?): String? =
        href?.trimEnd('/')?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            ?.uppercase()

    /** Absolute cover URL for coil; calibre-web's /opds/cover/{id}. */
    open fun coverUrl(server: CalibreServer, entry: OpdsEntry): String {
        val href = entry.coverHref ?: entry.bookId.takeIf { it > 0 }?.let { "/opds/cover/$it" } ?: ""
        return absolute(server, href)
    }

    /** Value of the HTTP Authorization header for cover requests (coil ImageRequest). */
    open fun coverAuthHeader(server: CalibreServer): String? {
        if (server.username.isEmpty()) return null
        val raw = "${server.username}:${server.password}".encodeToByteArray()
        return "Basic " + Base64.encode(raw)
    }

    /** Cheap connectivity probe used by the settings dialog's 测试连接 button. */
    suspend fun ping(server: CalibreServer): Boolean = try {
        val response = client.get(absolute(server, "opds")) {
            if (server.username.isNotEmpty()) basicAuth(server.username, server.password)
        }
        response.status.isSuccess()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Cover image bytes for seed-color extraction (dynamic theme). */
    suspend fun fetchCoverBytes(server: CalibreServer, coverUrl: String): ByteArray = try {
        val response = client.get(coverUrl) {
            if (server.username.isNotEmpty()) basicAuth(server.username, server.password)
        }
        if (!response.status.isSuccess()) {
            throw CalibreWebApiException("封面下载失败 HTTP ${response.status.value}")
        }
        response.bodyAsBytes()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: CalibreWebApiException) {
        throw e
    } catch (e: Exception) {
        throw CalibreWebApiException("封面下载失败: ${e.message}", e)
    }

    private suspend fun feed(server: CalibreServer, path: String, offset: Int): OpdsFeed {
        val url = absolute(server, path) + "?offset=$offset"
        return try {
            val body = client.get(url) {
                if (server.username.isNotEmpty()) basicAuth(server.username, server.password)
            }.bodyAsText()
            OpdsParser.parse(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CalibreWebApiException("加载书架失败: ${e.message}", e)
        }
    }

    private fun absolute(server: CalibreServer, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("/") -> server.root + href
        else -> server.root + "/" + href
    }

    /** Percent-encodes a path segment (CJK titles, spaces) so OPDS search URLs stay legal. */
    private fun encodePath(query: String): String =
        query.encodeURLPathPart()
}
