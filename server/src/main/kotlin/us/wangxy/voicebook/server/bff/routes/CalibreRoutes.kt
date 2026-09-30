package us.wangxy.voicebook.server.bff.routes

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
        val uploads = services.content?.books(call.bffPrincipal.username, query).orEmpty().filter { category == null || category == "电子书" || category in it.tags }
        val own = uploads.drop(offset).take(limit)
        val remote = if (library.available) library.books(query, (offset - uploads.size).coerceAtLeast(0), (limit - own.size).coerceAtLeast(1), category) else null
        call.respond(us.wangxy.voicebook.bff.contract.CalibreBookPage(
            own + if (own.size < limit) remote?.items.orEmpty().take(limit - own.size) else emptyList(),
            uploads.size + (remote?.total ?: 0), offset, limit))
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
        call.respond(if (store != null) store.progress(call.bffPrincipal.username, id) else progress.read(call.bffPrincipal.username, id))
    }

    put("${BffRoutes.CALIBRE_PROGRESS}/{bookId}") {
        val update = call.receive<ReadingProgressUpdate>()
        if (!FORMAT.matches(update.format)) throw BffException.badRequest("格式非法")
        if (update.position.length > 4096) throw BffException.badRequest("position 过长")
        val id = call.longPath("bookId")
        val store = services.content
        if (id < 0) store?.book(call.bffPrincipal.username, id) ?: throw BffException.notFound("书籍不存在")
        else library.book(id)
        if (store != null) store.setProgress(call.bffPrincipal.username, id, update)
        else progress.write(call.bffPrincipal.username, id, update)
        call.respond(HttpStatusCode.NoContent)
    }

    post(BffRoutes.CALIBRE_ACTIVATE) {
        val request = call.receive<CalibreActivateRequest>()
        call.respond(services.accounts.activate(BffComponents.CALIBRE, call.bffPrincipal.username, request.password))
    }
}
