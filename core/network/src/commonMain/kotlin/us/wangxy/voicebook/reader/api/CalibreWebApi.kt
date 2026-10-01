package us.wangxy.voicebook.reader.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLPathPart
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.contract.ApiError
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CalibreBook
import us.wangxy.voicebook.bff.contract.CalibreBookPage
import us.wangxy.voicebook.reader.opds.OpdsEntry
import us.wangxy.voicebook.reader.opds.OpdsFeed
import us.wangxy.voicebook.reader.opds.OpdsParser
import kotlin.io.encoding.Base64

open class CalibreWebApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Download reached the server but it refused (404 = book has no such format). */
class DownloadHttpException(val code: Int, message: String) : CalibreWebApiException(message)

/**
 * Signed-in users use the BFF. Signed-out users may keep a separately configured
 * calibre-web account and access its OPDS catalog with Basic authentication.
 */
open class CalibreWebApi(
    private val client: HttpClient,
    private val session: BffSession? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val documentIds = mutableMapOf<String, String>()

    suspend fun catalogue(server: CalibreServer, query: String, category: String, offset: Int, tags: List<String> = emptyList(), tagMode: String = "any", sort: String = "added"): OpdsFeed =
        if (server.viaBff) bffBooks(server, query.takeIf(String::isNotBlank), offset, category, tags, tagMode, sort)
        else if (query.isNotBlank()) feed(server, "opds/search/" + encodePath(query), offset)
        else feed(server, "opds/new", offset)

    open suspend fun newest(server: CalibreServer, offset: Int = 0): OpdsFeed =
        if (server.viaBff) bffBooks(server, null, offset) else feed(server, "opds/new", offset)

    open suspend fun search(server: CalibreServer, query: String, offset: Int = 0): OpdsFeed =
        if (server.viaBff) bffBooks(server, query, offset) else feed(server, "opds/search/" + encodePath(query), offset)

    /**
     * Downloads the book file for reading. Prefers the feed's own acquisition [href] (its last
     * path segment names the format); falls back to the backend's per-format download route.
     * 404 means the book has no such format.
     */
    suspend fun downloadBook(server: CalibreServer, bookId: Int, href: String?, format: String): ByteArray {
        val url = when {
            !href.isNullOrBlank() -> absolute(server, href)
            server.viaBff -> absolute(server, BffRoutes.calibreFile(bookId.toLong(), format))
            else -> absolute(server, "/opds/download/$bookId/${format.uppercase()}/")
        }
        val auth = authorization(server)
        return try {
            val response = client.get(url) { auth?.let { header(HttpHeaders.Authorization, it) } }
            if (!response.status.isSuccess()) {
                throw DownloadHttpException(response.status.value, "下载失败 HTTP ${response.status.value}")
            }
            response.bodyAsBytes().also { rememberDocument(server, bookId, format, it) }
        } catch (e: CalibreWebApiException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CalibreWebApiException("下载失败: ${e.message}", e)
        }
    }

    /** Registers bytes loaded from the local book cache so CWA can identify the document too. */
    fun rememberDocument(server: CalibreServer, bookId: Int, format: String, bytes: ByteArray) {
        if (!server.viaBff) documentIds[documentKey(server, bookId, format)] = koreaderPartialMd5(bytes)
    }

    fun documentId(server: CalibreServer, bookId: Int, format: String): String? =
        documentIds[documentKey(server, bookId, format)]

    open suspend fun saveKoreaderProgress(server: CalibreServer, document: String, position: String, percent: Int): KosyncProgress {
        require(!server.viaBff)
        require(document.matches(Regex("[0-9a-f]{32}")))
        val auth = authorization(server)
        val response = client.put(absolute(server, "/kosync/syncs/progress")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(KosyncProgressUpdate(document, position, (percent.coerceIn(0, 100) / 100.0), "VoiceBook", "voicebook"))
        }
        return kosyncResponse(response).also {
            if (it.document != document || it.timestamp == null) {
                throw CalibreWebApiException("CWA 未确认保存阅读进度，本地进度将保留重试")
            }
        }
    }

    suspend fun loadKoreaderProgress(server: CalibreServer, document: String): KosyncProgress? {
        if (server.viaBff) return null
        val auth = authorization(server)
        val response = client.get(absolute(server, "/kosync/syncs/progress/${document.encodeURLPathPart()}")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }
        return kosyncResponse(response).takeIf { it.progress != null }
    }

    private suspend fun kosyncResponse(response: HttpResponse): KosyncProgress {
        if (!response.status.isSuccess()) {
            val reason = when (response.status.value) {
                401, 403 -> "认证失败，请检查书库账号"
                503 -> "请在 CWA 设置中开启 KOReader Sync"
                404, 405 -> "服务端未提供 KOSync 进度接口"
                else -> "HTTP ${response.status.value}"
            }
            throw CalibreWebApiException("CWA 进度同步失败：$reason")
        }
        val result = try { json.decodeFromString<KosyncProgress>(response.bodyAsText()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw CalibreWebApiException("CWA 未返回有效进度 JSON（可能返回了登录页）", e) }
        if (result.error != null) throw CalibreWebApiException("CWA 进度同步失败：${result.message ?: result.error}")
        return result
    }

    /**
     * Persists the EPUB reader position in a separately configured calibre-web account.
     * calibre-web exposes this as the same bookmark endpoint used by its web reader.
     * The bookmark value is intentionally opaque, so CFI/page positions can be sent unchanged.
     */
    open suspend fun saveBookmark(server: CalibreServer, bookId: Int, format: String, position: String) {
        if (server.viaBff) return
        val auth = authorization(server)
        val response = client.submitForm(
            absolute(server, "/ajax/bookmark/$bookId/${format.uppercase()}"),
            formParameters = Parameters.build { append("bookmark", position) },
        ) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }
        if (!response.status.isSuccess()) {
            throw CalibreWebApiException("保存阅读进度失败 HTTP ${response.status.value}")
        }
    }

    /** Format segment of a download href (/opds/download/123/PDF/ → PDF), null when absent. */
    fun formatFromHref(href: String?): String? =
        href?.trimEnd('/')?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            ?.uppercase()

    /** Absolute cover URL for coil; calibre-web's /opds/cover/{id} or the BFF's cover route. */
    open fun coverUrl(server: CalibreServer, entry: OpdsEntry): String {
        if (server.viaBff) return entry.coverHref?.let { absolute(server, it) } ?: ""
        val href = entry.coverHref ?: entry.bookId.takeIf { it > 0 }?.let { "/opds/cover/$it" } ?: ""
        return absolute(server, href)
    }

    /**
     * Cover URL for a book known only by id (reading history). History rows keep the URL of the
     * backend they were opened from, so it is rebuilt when that no longer matches [server].
     */
    fun coverUrlForBook(server: CalibreServer, bookId: Int, storedUrl: String): String = when {
        storedUrl.startsWith(server.root + "/") -> storedUrl
        server.viaBff -> absolute(server, BffRoutes.calibreCover(bookId.toLong()))
        else -> absolute(server, "/opds/cover/$bookId")
    }

    /** Value of the HTTP Authorization header for cover requests (coil ImageRequest). */
    open fun coverAuthHeader(server: CalibreServer): String? {
        if (server.viaBff) return session?.currentAccessToken()?.let { "Bearer $it" }
        return basicHeader(server)
    }

    /** Cheap connectivity probe used by the settings dialog's 测试连接 button. */
    suspend fun ping(server: CalibreServer): Boolean = try {
        val auth = authorization(server)
        val url = if (server.viaBff) absolute(server, BffRoutes.CALIBRE_BOOKS) + "?limit=1" else absolute(server, "opds")
        val response = client.get(url) { auth?.let { header(HttpHeaders.Authorization, it) } }
        when {
            response.status.isSuccess() -> true
            response.status.value == 401 -> throw CalibreWebApiException(
                "认证被拒（401）：用户名或密码不正确，或该 calibre-web 账号未开启 OPDS 权限",
            )
            else -> false
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: CalibreWebApiException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Cover image bytes for seed-color extraction (dynamic theme). */
    suspend fun fetchCoverBytes(server: CalibreServer, coverUrl: String): ByteArray = try {
        val auth = authorization(server)
        val response = client.get(coverUrl) { auth?.let { header(HttpHeaders.Authorization, it) } }
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
        val auth = basicHeader(server)
        return try {
            val body = client.get(url) { auth?.let { header(HttpHeaders.Authorization, it) } }.bodyAsText()
            OpdsParser.parse(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CalibreWebApiException("加载书架失败: ${e.message}", e)
        }
    }

    private suspend fun bffBooks(server: CalibreServer, query: String?, offset: Int, category: String = "全部", tags: List<String> = emptyList(), tagMode: String = "any", sort: String = "added"): OpdsFeed {
        val auth = authorization(server)
        return try {
            val response = client.get(absolute(server, BffRoutes.CALIBRE_BOOKS)) {
                if (tags.isNotEmpty()) parameter("tags", tags.joinToString(","))
                if (tagMode != "any") parameter("tagMode", tagMode)
                if (sort != "added") parameter("sort", sort)
                parameter("offset", offset)
                parameter("limit", BFF_PAGE_SIZE)
                query?.let { parameter("q", it) }
                if (category != "全部") parameter("category", category)
                auth?.let { header(HttpHeaders.Authorization, it) }
            }
            if (!response.status.isSuccess()) throw CalibreWebApiException("加载书架失败: ${bffError(response)}")
            val page = json.decodeFromString(CalibreBookPage.serializer(), response.bodyAsText())
            val next = offset + page.items.size
            OpdsFeed(page.items.map { it.toEntry() }, nextOffset = next.takeIf { page.items.isNotEmpty() && it < page.total }, libraryVersion = page.libraryVersion)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: CalibreWebApiException) {
            throw e
        } catch (e: Exception) {
            throw CalibreWebApiException("加载书架失败: ${e.message}", e)
        }
    }

    fun CalibreBook.toEntry(): OpdsEntry {
        val format = PreferredFormats.firstOrNull { it in formats.map(String::uppercase) } ?: formats.firstOrNull()
        return OpdsEntry(
            bookId = id.toInt(),
            title = title,
            author = authors.joinToString(" & "),
            summary = description.orEmpty(),
            coverHref = if (hasCover) BffRoutes.calibreCover(id) else null,
            epubHref = format?.let { BffRoutes.calibreFile(id, it) },
            tags=tags,series=series,publisher=publisher,rating=rating,pageCount=pageCount,languages=languages,
            identifiers=identifiers,edition=edition,pubdate=pubdate,lastModified=lastModified,description=description,
        )
    }

    private suspend fun bffError(response: HttpResponse): String {
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return runCatching { json.decodeFromString(ApiError.serializer(), body).message }
            .getOrElse { "HTTP ${response.status.value}" }
    }

    /** Header for [server]: the session's Bearer token for the BFF, Basic for calibre-web (null = anonymous). */
    private suspend fun authorization(server: CalibreServer): String? {
        if (!server.viaBff) return basicHeader(server)
        val token = session?.accessToken() ?: throw CalibreWebApiException("登录已过期，请在「我的」重新登录")
        return "Bearer $token"
    }

    private fun basicHeader(server: CalibreServer): String? {
        if (server.username.isEmpty()) return null
        val raw = "${server.username}:${server.password}".encodeToByteArray()
        return "Basic " + Base64.encode(raw)
    }

    private fun absolute(server: CalibreServer, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("/") -> server.root + href
        else -> server.root + "/" + href
    }

    private fun documentKey(server: CalibreServer, bookId: Int, format: String) =
        "${server.root}|$bookId|${format.uppercase()}"

    /** Percent-encodes a path segment (CJK titles, spaces) so OPDS search URLs stay legal. */
    private fun encodePath(query: String): String =
        query.encodeURLPathPart()

    private companion object {
        /** Matches the shelf's SHELF_PAGE_SIZE: a short page is what ends the pager. */
        const val BFF_PAGE_SIZE = 20

        /** Download format picked for a BFF book, in order of preference (all readable by the reader). */
        val PreferredFormats = listOf("EPUB", "KEPUB", "PDF", "TXT")
    }
}

@Serializable
data class KosyncProgressUpdate(
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String,
    val device_id: String,
)

@Serializable
data class KosyncProgress(
    val document: String? = null,
    val progress: String? = null,
    val percentage: Double? = null,
    val timestamp: Long? = null,
    val calibre_book_id: Int? = null,
    val calibre_book_format: String? = null,
    val device: String? = null,
    val error: Int? = null,
    val message: String? = null,
)

/** The runtime-only BFF catalog while signed in, null when signed out. */
fun BffSession.calibreServer(): CalibreServer? =
    signedInUser.value?.let { CalibreServer(baseUrl = baseUrl(), viaBff = true) }
