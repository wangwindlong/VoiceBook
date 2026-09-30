package us.wangxy.voicebook.server.bff.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.time.Instant
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffServices

fun Route.eventRoutes(services: BffServices) {
    val store = services.content ?: return
    val profile = services.profile ?: return
    post(BffRoutes.EVENTS) {
        val batch = call.receive<EventBatch>()
        if (batch.events.size > 200) throw BffException.badRequest("最多 200 条事件")
        val owner = call.bffPrincipal.username
        val events = batch.events.map { event ->
            if (event.kind !in setOf("open_book","finish_book","reading_session","search") ||
                event.objectType !in setOf("book","entry","feed","comment") || event.objectId.length !in 1..512 ||
                event.value?.isFinite() == false || (event.seconds != null && event.seconds !in 0L..86400L) || (event.device?.length ?: 0) > 100)
                throw BffException.badRequest("事件参数非法")
            val timestamp = runCatching { Instant.parse(event.ts) }.getOrElse { throw BffException.badRequest("时间格式非法") }
            if (timestamp.isAfter(Instant.now().plusSeconds(300))) throw BffException.badRequest("事件时间超前")
            if (event.kind != "search") {
                if (event.objectType != "book") throw BffException.badRequest("阅读事件需指定书籍")
                val id = event.objectId.toLongOrNull() ?: throw BffException.badRequest("书籍编号非法")
                if (id < 0) store.book(owner,id) else services.calibreLibrary.book(id)
            }
            event.copy(ts=timestamp.toString())
        }
        store.record(owner,events,"client")
        call.respond(HttpStatusCode.NoContent)
    }
    get(BffRoutes.PROFILE) { call.respond(profile.profile(call.bffPrincipal.username)) }
    put(BffRoutes.PROFILE) {
        val tags = call.receive<InterestSelection>().tags.distinct()
        if (tags.size > 5 || tags.any { it.isBlank() || it.length > 100 }) throw BffException.badRequest("请选择最多 5 个兴趣")
        val owner = call.bffPrincipal.username
        val known = ((if (services.calibreLibrary.available) services.calibreLibrary.tags().map { it.name } else emptyList()) + profile.feedCategories(owner).values + store.categories(owner).categories.values).map(store::normalizeTag).toSet()
        if (tags.any { store.normalizeTag(it) !in known }) throw BffException.badRequest("请选择已有分类或书籍标签")
        profile.select(owner,tags)
        call.respond(HttpStatusCode.NoContent)
    }
    delete("${BffRoutes.PROFILE}/{tag}") {
        val tag = call.parameters["tag"] ?: throw BffException.badRequest("缺少标签")
        profile.mute(call.bffPrincipal.username,tag)
        call.respond(HttpStatusCode.NoContent)
    }
    get(BffRoutes.FOR_YOU) { call.respond(profile.forYou(call.bffPrincipal.username,call.intParam("limit",20,1..200))) }
}
