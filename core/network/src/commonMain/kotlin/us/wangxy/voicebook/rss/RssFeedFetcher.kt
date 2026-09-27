package us.wangxy.voicebook.rss

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/** Raw feed XML source; interface so tests can inject canned feeds. */
interface FeedTextFetcher {
    suspend fun fetchFeedText(url: String): String
}

/** Fetches raw feed XML with a browser-like UA (some servers 403 unknown agents). */
class RssFeedFetcher(private val client: HttpClient) : FeedTextFetcher {

    override suspend fun fetchFeedText(url: String): String {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (!response.status.value.let { it in 200..299 }) {
            throw RssHttpException(response.status.value, "订阅源请求失败 HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 VoiceBook/1.0"
    }
}

class RssHttpException(val code: Int, message: String) : Exception(message)
