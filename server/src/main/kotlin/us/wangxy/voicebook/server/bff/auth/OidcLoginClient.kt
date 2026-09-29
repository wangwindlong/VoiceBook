package us.wangxy.voicebook.server.bff.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.bff.contract.AuthTokens
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.config.OidcConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Password login on behalf of the app. Authelia has no password grant, so the BFF plays the
 * browser: first factor with a throwaway session, the PKCE authorization request (the redirect is
 * read, never followed; the client must use implicit consent), then the code exchange. The web
 * session is logged out afterwards; only the OIDC tokens outlive the call.
 */
class OidcLoginClient(
    private val http: HttpClient,
    private val config: OidcConfig,
    private val sessionEngine: () -> HttpClientEngine = { OkHttp.create() },
) {
    private val log = LoggerFactory.getLogger(OidcLoginClient::class.java)
    private val random = SecureRandom()

    suspend fun login(username: String, password: String, clientIp: String?): AuthTokens {
        val verifier = base64Url(ByteArray(32).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        // Authelia rejects state shorter than 8 characters.
        val state = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        withSessionClient { client ->
            val firstFactor = send {
                client.post("${config.issuer}/api/firstfactor") {
                    contentType(ContentType.Application.Json)
                    clientIp?.let { header("X-Forwarded-For", it) }
                    setBody(
                        buildJsonObject {
                            put("username", username)
                            put("password", password)
                            put("keepMeLoggedIn", false)
                        }.toString(),
                    )
                }
            }
            if (firstFactor.status == HttpStatusCode.Unauthorized || firstFactor.status == HttpStatusCode.Forbidden) {
                throw BffException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "用户名或密码错误")
            }
            if (!firstFactor.status.isSuccess()) {
                throw BffException.upstream("oidc", "认证服务登录失败 HTTP ${firstFactor.status.value}")
            }

            try {
                val authorization = send {
                    client.get("${config.issuer}/api/oidc/authorization") {
                        parameter("client_id", config.clientId)
                        parameter("redirect_uri", config.redirectUri)
                        parameter("response_type", "code")
                        parameter("scope", config.scopes)
                        parameter("state", state)
                        parameter("code_challenge", challenge)
                        parameter("code_challenge_method", "S256")
                    }
                }
                val location = authorization.headers[HttpHeaders.Location]
                if (authorization.status.value !in 300..399 || location == null) {
                    throw BffException.upstream("oidc", "授权端点未返回重定向 HTTP ${authorization.status.value}")
                }
                if (!location.startsWith(config.redirectUri)) {
                    // Sent back to Authelia's portal: second factor or consent page required.
                    throw BffException(
                        HttpStatusCode.Forbidden,
                        "INTERACTION_REQUIRED",
                        "该账号需要二次验证或授权确认，App 暂不支持，请联系管理员",
                    )
                }
                val params = parseQueryString(location.substringAfter('?', ""))
                params["error"]?.let { throw BffException.upstream("oidc", "授权失败: $it ${params["error_description"].orEmpty()}") }
                if (params["state"] != state) throw BffException.upstream("oidc", "授权回调 state 不匹配")
                val code = params["code"] ?: throw BffException.upstream("oidc", "授权回调缺少 code")

                return tokenRequest(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to config.redirectUri,
                    "client_id" to config.clientId,
                    "code_verifier" to verifier,
                )
            } finally {
                runCatching {
                    client.post("${config.issuer}/api/logout") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                }.onFailure { log.debug("authelia web logout failed: {}", it.message) }
            }
        }
    }

    suspend fun refresh(refreshToken: String): AuthTokens = tokenRequest(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    )

    /** Best effort (RFC 7009): failures are logged, never surfaced to the app. */
    suspend fun revoke(token: String, hint: String) {
        try {
            val response = http.submitForm(
                url = "${config.issuer}/api/oidc/revocation",
                formParameters = parameters {
                    append("token", token)
                    append("token_type_hint", hint)
                    append("client_id", config.clientId)
                },
            )
            if (!response.status.isSuccess()) log.warn("token revocation HTTP {}", response.status.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("token revocation failed: {}", e.message)
        }
    }

    private suspend fun tokenRequest(vararg form: Pair<String, String>): AuthTokens {
        val response = send {
            http.submitForm(
                url = "${config.issuer}/api/oidc/token",
                formParameters = parameters { form.forEach { (k, v) -> append(k, v) } },
            )
        }
        val body = runCatching { BffJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        if (!response.status.isSuccess()) {
            val error = body?.string("error")
            if (error == "invalid_grant" || response.status == HttpStatusCode.Unauthorized) {
                throw BffException(HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "登录已过期，请重新登录")
            }
            throw BffException.upstream("oidc", "换取 token 失败 HTTP ${response.status.value} ${error.orEmpty()}")
        }
        val accessToken = body?.string("access_token") ?: throw BffException.upstream("oidc", "token 响应缺少 access_token")
        return AuthTokens(
            accessToken = accessToken,
            refreshToken = body.string("refresh_token"),
            expiresIn = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600,
            tokenType = body.string("token_type") ?: "Bearer",
        )
    }

    /** A client built on a supplied engine does not own it, so the engine is closed here. */
    private inline fun <T> withSessionClient(block: (HttpClient) -> T): T {
        val engine = sessionEngine()
        try {
            return HttpClient(engine) {
                expectSuccess = false
                followRedirects = false
                install(HttpCookies)
                install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            }.use(block)
        } finally {
            engine.close()
        }
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffException.upstream("oidc", "无法连接认证服务", e)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
