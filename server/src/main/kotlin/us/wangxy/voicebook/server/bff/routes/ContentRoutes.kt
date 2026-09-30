package us.wangxy.voicebook.server.bff.routes

import io.ktor.server.routing.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpStatusCode
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.content.ContentStore

fun Route.contentRoutes(store: ContentStore) {
    post(BffRoutes.BOOK_UPLOAD) {
        val p = call.request.queryParameters
        val filename = p["filename"] ?: throw BffException.badRequest("缺少文件名")
        val channel = call.receiveChannel()
        val book = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val staged = store.createUpload()
            try {
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        size += count
                        if (size > ContentStore.MAX_UPLOAD) throw BffException.badRequest("图书不能超过 64MB")
                        output.write(buffer, 0, count)
                    }
                }
                store.addFile(call.bffPrincipal.username, filename, p["title"] ?: filename.substringBeforeLast('.'), p["author"].orEmpty(), p["category"] ?: "电子书", staged)
            } finally { staged.delete() }
        }
        call.respond(HttpStatusCode.Created, book)
    }
    get("${BffRoutes.ARTICLE_REACTIONS}/{key}") {
        call.respond(store.reaction(call.bffPrincipal.username, contentKey(call.parameters["key"])))
    }
    put("${BffRoutes.ARTICLE_REACTIONS}/{key}") {
        val update = call.receive<ArticleReactionUpdate>()
        call.respond(store.setReaction(call.bffPrincipal.username, contentKey(call.parameters["key"]), update.liked))
    }
    get(BffRoutes.FEED_CATEGORIES) { call.respond(store.categories(call.bffPrincipal.username)) }
    put("${BffRoutes.FEED_CATEGORIES}/{key}") {
        val category = call.receive<FeedCategoryUpdate>().category.trim()
        if (category.isEmpty() || category.length > 40) throw BffException.badRequest("分类需为 1-40 字符")
        store.setCategory(call.bffPrincipal.username, contentKey(call.parameters["key"]), category)
        call.respond(HttpStatusCode.NoContent)
    }
}
private fun contentKey(value: String?): String = value?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,200}")) }
    ?: throw BffException.badRequest("内容标识无效")
