package us.wangxy.voicebook.server.bff.routes

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import us.wangxy.voicebook.bff.contract.*
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import us.wangxy.voicebook.bff.contract.BffComponents
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CalibreActivateRequest
import us.wangxy.voicebook.bff.contract.ReadingProgressUpdate
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffServices

private val FORMAT = Regex("^[A-Za-z0-9]{1,10}$")

fun Route.calibreRoutes(services: BffServices) {
    val library = services.calibreLibrary
    val progress = services.calibreProgress

    get(BffRoutes.CALIBRE_BOOKS) {
        val offset = call.intParam("offset", 0, 0..Int.MAX_VALUE)
        val limit = call.intParam("limit", 30, 1..100)
        val query = call.request.queryParameters["q"]
        val category = call.request.queryParameters["category"]?.takeUnless { it == "全部" }
        val owner = call.bffPrincipal.username
        val tags = call.request.queryParameters["tags"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct().orEmpty()
        if (tags.size > 30 || tags.any { it.length > 100 }) throw BffException.badRequest("标签过多或过长")
        val mode = call.request.queryParameters["tagMode"] ?: "any"
        val sort = call.request.queryParameters["sort"] ?: "added"
        if (mode !in setOf("any", "all") || sort !in setOf("added", "title", "author", "rating", "pubdate")) throw BffException.badRequest("筛选参数非法")
        val uploads = services.content?.books(owner, query).orEmpty().filter { book ->
            (category == null || category == "电子书" || category in book.tags) &&
                (tags.isEmpty() || if (mode == "all") book.tags.containsAll(tags) else tags.any { it in book.tags })
        }.let { books -> when (sort) { "title" -> books.sortedBy { it.title }; "author" -> books.sortedBy { it.authors.firstOrNull() }; else -> books } }
        val own = uploads.drop(offset).take(limit)
        val remote = if (library.available) library.books(query, (offset - uploads.size).coerceAtLeast(0), (limit - own.size).coerceAtLeast(1), category, tags, mode, sort) else null
        val uploadVersion = services.content?.books(owner).orEmpty().joinToString { "${it.id}:${it.title}:${it.tags}" }.hashCode()
        call.respond(CalibreBookPage(
            own + if (own.size < limit) remote?.items.orEmpty().take(limit - own.size) else emptyList(),
            uploads.size + (remote?.total ?: 0), offset, limit, "${remote?.libraryVersion.orEmpty()}-$uploadVersion"))
    }

    get(BffRoutes.CALIBRE_TAGS) {
        val uploadTags = services.content?.books(call.bffPrincipal.username).orEmpty().flatMap { it.tags }.groupingBy { it }.eachCount()
        val counts = (if (library.available) library.tags() else emptyList()).associate { it.name to it.count }.toMutableMap()
        uploadTags.forEach { (tag,count) -> counts[tag] = (counts[tag] ?: 0) + count }
        call.respond(CalibreTagList(counts.map { CalibreTag(it.key,it.value) }.sortedWith(compareByDescending<CalibreTag> { it.count }.thenBy { it.name })))
    }

    get(BffRoutes.CALIBRE_HISTORY) {
        val owner = call.bffPrincipal.username
        val page = services.content?.history(owner,call.intParam("limit",50,1..200)) ?: ReadingHistoryPage()
        call.respond(page.copy(items=page.items.map { item ->
            val book = try { if (item.bookId < 0) services.content?.book(owner,item.bookId) else library.book(item.bookId) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: BffException) { null }
            item.copy(title=book?.title,author=book?.authors?.joinToString(" & "),coverUrl=book?.takeIf { it.hasCover }?.let { BffRoutes.calibreCover(it.id) })
        }))
    }

    get("${BffRoutes.CALIBRE_BOOKS}/{id}") {
        val id = call.longPath("id")
        call.respond(if (id < 0) services.content?.book(call.bffPrincipal.username, id) ?: throw BffException.notFound("书籍不存在") else library.book(id))
    }

    get("${BffRoutes.CALIBRE_BOOKS}/{id}/cover") {
        val file = library.coverFile(call.longPath("id"))
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respondFile(file)
    }

    get("${BffRoutes.CALIBRE_BOOKS}/{id}/file/{format}") {
        val id = call.longPath("id")
        val format = call.parameters["format"]?.takeIf { FORMAT.matches(it) }
            ?: throw BffException.badRequest("格式非法")
        val file = if (id < 0) services.content?.file(call.bffPrincipal.username, id, format) ?: throw BffException.notFound("书籍不存在") else library.bookFile(id, format)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, "book-$id.${format.lowercase()}")
                .toString(),
        )
        call.respondFile(file)
    }

    get("${BffRoutes.CALIBRE_PROGRESS}/{bookId}") {
        val id = call.longPath("bookId")
        val store = services.content
        if (id < 0) store?.book(call.bffPrincipal.username, id) ?: throw BffException.notFound("书籍不存在")
        else library.book(id)
        call.respond(if (store != null) store.progress(call.bffPrincipal.username, id) else ReadingProgress(id))
    }

    put("${BffRoutes.CALIBRE_PROGRESS}/{bookId}") {
        val update = call.receive<ReadingProgressUpdate>()
        if (!FORMAT.matches(update.format)) throw BffException.badRequest("格式非法")
        if (update.position.length > 4096) throw BffException.badRequest("position 过长")
        val id = call.longPath("bookId")
        val store = services.content
        if (id < 0) store?.book(call.bffPrincipal.username, id) ?: throw BffException.notFound("书籍不存在")
        else library.book(id)
        val owner = call.bffPrincipal.username
        if (store == null) throw BffException.upstream("content", "进度存储不可用")
        store.setProgress(owner, id, update)
        services.background.launch { services.mirrorMutex.withLock { progress.write(owner, id, store.progress(owner,id).percent) } }
        call.respond(HttpStatusCode.NoContent)
    }

    post(BffRoutes.CALIBRE_ACTIVATE) {
        val request = call.receive<CalibreActivateRequest>()
        call.respond(services.accounts.activate(BffComponents.CALIBRE, call.bffPrincipal.username, request.password))
    }
}
