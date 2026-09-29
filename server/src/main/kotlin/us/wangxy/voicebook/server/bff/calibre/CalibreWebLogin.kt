package us.wangxy.voicebook.server.bff.calibre

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import us.wangxy.voicebook.server.bff.config.CalibreConfig

/**
 * CWA only creates its local user row (needed for bookmarks) when someone logs in through its
 * LDAP form. The BFF does that once on the user's behalf whenever it holds a plaintext password
 * (register, password change, explicit activate).
 */
class CalibreWebLogin(
    private val config: CalibreConfig,
    private val engineFactory: () -> HttpClientEngine = { OkHttp.create() },
) {
    /** True when CWA accepted the credentials (redirect away from /login). */
    suspend fun login(username: String, password: String): Boolean {
        val engine = engineFactory()
        try {
            return loginWith(engine, username, password)
        } finally {
            engine.close()
        }
    }

    private suspend fun loginWith(engine: HttpClientEngine, username: String, password: String): Boolean {
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpCookies)
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }.use { client ->
            val page = client.get("${config.webUrl}/login")
            val csrf = CSRF_PATTERNS.firstNotNullOfOrNull { it.find(page.bodyAsText())?.groupValues?.get(1) }
            val response = client.submitForm(
                url = "${config.webUrl}/login",
                formParameters = parameters {
                    append("username", username)
                    append("password", password)
                    append("remember_me", "on")
                    append("next", "/")
                    if (csrf != null) append("csrf_token", csrf)
                },
            )
            val location = response.headers[HttpHeaders.Location].orEmpty()
            return response.status in REDIRECTS && !location.contains("login")
        }
    }

    private companion object {
        val REDIRECTS = setOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.MovedPermanently)
        val CSRF_PATTERNS = listOf(
            Regex("""name="csrf_token"[^>]*value="([^"]+)""""),
            Regex("""value="([^"]+)"[^>]*name="csrf_token""""),
        )
    }
}
