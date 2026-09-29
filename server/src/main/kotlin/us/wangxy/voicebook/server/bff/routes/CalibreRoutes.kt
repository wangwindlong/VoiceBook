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
        call.respond(library.books(call.request.queryParameters["q"], offset, limit))
    }

    get("${BffRoutes.CALIBRE_BOOKS}/{id}") {
        call.respond(library.book(call.longPath("id")))
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
        val file = library.bookFile(id, format)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, "book-$id.${format.lowercase()}")
                .toString(),
        )
        call.respondFile(file)
    }

    get("${BffRoutes.CALIBRE_PROGRESS}/{bookId}") {
        call.respond(progress.read(call.bffPrincipal.username, call.longPath("bookId")))
    }

    put("${BffRoutes.CALIBRE_PROGRESS}/{bookId}") {
        val update = call.receive<ReadingProgressUpdate>()
        if (!FORMAT.matches(update.format)) throw BffException.badRequest("格式非法")
        if (update.position.length > 4096) throw BffException.badRequest("position 过长")
        progress.write(call.bffPrincipal.username, call.longPath("bookId"), update)
        call.respond(HttpStatusCode.NoContent)
    }

    post(BffRoutes.CALIBRE_ACTIVATE) {
        val request = call.receive<CalibreActivateRequest>()
        call.respond(services.accounts.activate(BffComponents.CALIBRE, call.bffPrincipal.username, request.password))
    }
}
