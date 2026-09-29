package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.CommentCreateRequest

/**
 * Artalk comments through the BFF, which exchanges the session for an Artalk token server-side.
 * The app has no direct Artalk login, so comments need a signed-in session: without one every
 * call fails with a 401 [BffApiException].
 *
 * Artalk's own response bodies are passed through untouched and returned as raw JSON, the same
 * "parse only what you need" stance as [us.wangxy.voicebook.rss.MinifluxApi].
 */
class ArtalkApi(
    private val client: HttpClient,
    private val session: BffSession,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [pageKey] must start with `/` (e.g. `/book/42`); [sortBy] is `date_asc`, `date_desc` or `vote`. */
    suspend fun comments(pageKey: String, offset: Int = 0, limit: Int = 20, sortBy: String? = null): JsonObject {
        val token = requireToken()
        val response = bffCall {
            client.get(url(session.baseUrl(), BffRoutes.ARTALK_COMMENTS)) {
                bearerAuth(token)
                parameter("page_key", pageKey)
                parameter("offset", offset)
                parameter("limit", limit)
                sortBy?.let { parameter("sort_by", it) }
            }
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Posts as the signed-in user; the BFF fills in name and email from the account. */
    suspend fun post(request: CommentCreateRequest): JsonObject {
        val token = requireToken()
        val response = bffCall {
            client.post(url(session.baseUrl(), BffRoutes.ARTALK_COMMENTS)) {
                bearerAuth(token)
                json(request)
            }
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun requireToken(): String =
        session.accessToken() ?: throw BffApiException(401, "NOT_SIGNED_IN", "评论需要先在「我的」登录统一账号")
}
