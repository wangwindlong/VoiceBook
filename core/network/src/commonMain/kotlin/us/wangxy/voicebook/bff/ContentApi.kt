package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import us.wangxy.voicebook.bff.contract.*

/** Additional app features live in the BFF, with the same session as the upstream adapters. */
class ContentApi(private val client: HttpClient, private val session: BffSession) {
    private suspend fun token() = session.accessToken() ?: throw BffApiException(401, "NOT_SIGNED_IN", "请先登录")
    suspend fun history(limit: Int = 50): ReadingHistoryPage {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.CALIBRE_HISTORY)) { bearerAuth(access); parameter("limit",limit) } }.body()
    }
    suspend fun tags(): CalibreTagList {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.CALIBRE_TAGS)) { bearerAuth(access) } }.body()
    }
    suspend fun events(events: List<BehaviorEvent>, expectedOwner: String? = session.signedInUser.value) {
        val access = token()
        if (expectedOwner != session.signedInUser.value) return
        bffCall { client.post(url(session.baseUrl(), BffRoutes.EVENTS)) { bearerAuth(access); json(EventBatch(events)) } }
    }
    suspend fun profile(): InterestProfile {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.PROFILE)) { bearerAuth(access) } }.body()
    }
    suspend fun selectInterests(tags: List<String>) {
        val access = token()
        bffCall { client.put(url(session.baseUrl(), BffRoutes.PROFILE)) { bearerAuth(access); json(InterestSelection(tags)) } }
    }
    suspend fun muteInterest(tag: String) {
        val access = token()
        bffCall { client.delete(url(session.baseUrl(), BffRoutes.PROFILE + "/" + tag.encodeURLPathPart())) { bearerAuth(access) } }
    }
    suspend fun forYou(limit: Int = 4): ForYouResponse {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.FOR_YOU)) { bearerAuth(access); parameter("limit",limit) } }.body()
    }
    suspend fun book(id: Long): CalibreBook {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.calibreBook(id))) { bearerAuth(access) } }.body()
    }
    suspend fun upload(filename: String, title: String, author: String, category: String, bytes: ByteArray): CalibreBook {
        val access = token()
        return bffCall {
            client.post(url(session.baseUrl(), BffRoutes.BOOK_UPLOAD)) {
                bearerAuth(access)
                parameter("filename", filename); parameter("title", title); parameter("author", author); parameter("category", category)
                contentType(ContentType.Application.OctetStream); setBody(bytes)
            }
        }.body()
    }
    suspend fun reaction(key: String): ArticleReaction {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.articleReaction(key))) { bearerAuth(access) } }.body()
    }
    suspend fun setReaction(key: String, liked: Boolean): ArticleReaction {
        val access = token()
        return bffCall { client.put(url(session.baseUrl(), BffRoutes.articleReaction(key))) { bearerAuth(access); json(ArticleReactionUpdate(liked)) } }.body()
    }
    suspend fun categories(): Map<String, String> {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.FEED_CATEGORIES)) { bearerAuth(access) } }.body<FeedCategories>().categories
    }
    suspend fun setCategory(feed: String, category: String) {
        val access = token()
        bffCall { client.put(url(session.baseUrl(), BffRoutes.FEED_CATEGORIES + "/" + feed)) { bearerAuth(access); json(FeedCategoryUpdate(category)) } }
    }
    suspend fun progress(bookId: Long): ReadingProgress {
        val access = token()
        return bffCall { client.get(url(session.baseUrl(), BffRoutes.calibreProgress(bookId))) { bearerAuth(access) } }.body()
    }
    suspend fun setProgress(bookId: Long, progress: ReadingProgressUpdate, expectedOwner: String? = session.signedInUser.value) {
        val access = token()
        if (expectedOwner != session.signedInUser.value) throw BffApiException(401,"SESSION_CHANGED","账号已切换")
        bffCall { client.put(url(session.baseUrl(), BffRoutes.calibreProgress(bookId))) { bearerAuth(access); json(progress) } }
    }
}

/** Stable safe route key for arbitrary RSS GUIDs, without platform-specific hashing. */
fun contentKey(value: String): String {
    val first = value.fold(-3750763034362895579L) { hash, c -> (hash xor c.code.toLong()) * 1099511628211L }
    val second = value.reversed().fold(7809847782465536322L) { hash, c -> (hash xor c.code.toLong()) * 1099511628211L }
    return first.toULong().toString(16).padStart(16, '0') + second.toULong().toString(16).padStart(16, '0')
}
