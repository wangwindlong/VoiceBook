package us.wangxy.voicebook.bff

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import us.wangxy.voicebook.bff.contract.ApiError
import us.wangxy.voicebook.bff.contract.AuthConfigResponse
import us.wangxy.voicebook.bff.contract.AuthTokens
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.ChangePasswordRequest
import us.wangxy.voicebook.bff.contract.LoginRequest
import us.wangxy.voicebook.bff.contract.LoginResponse
import us.wangxy.voicebook.bff.contract.LogoutRequest
import us.wangxy.voicebook.bff.contract.MeResponse
import us.wangxy.voicebook.bff.contract.RefreshRequest
import us.wangxy.voicebook.bff.contract.RegisterRequest
import us.wangxy.voicebook.bff.contract.RegisterResponse

/**
 * Failure talking to the BFF. [status] 0 means the request never got an HTTP answer;
 * [message] is user-facing Chinese text (the BFF's own ApiError messages already are).
 *
 * [retryAfterSeconds] 是限频（429）响应 `Retry-After` 头里的秒数，供 UI 提示「N 秒后再试」。
 */
class BffApiException(
    val status: Int,
    val code: String,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = status == 401
}

/** Account endpoints of the BFF. [baseUrl] is passed per call because the user can change it on the login page. */
class BffAuthApi(private val client: HttpClient) {

    suspend fun config(baseUrl: String): AuthConfigResponse =
        call { client.get(url(baseUrl, BffRoutes.AUTH_CONFIG)) }.body()

    suspend fun login(baseUrl: String, request: LoginRequest): LoginResponse =
        call { client.post(url(baseUrl, BffRoutes.AUTH_LOGIN)) { json(request) } }.body()

    suspend fun register(baseUrl: String, request: RegisterRequest): RegisterResponse =
        call { client.post(url(baseUrl, BffRoutes.AUTH_REGISTER)) { json(request) } }.body()

    suspend fun refresh(baseUrl: String, refreshToken: String): AuthTokens =
        call { client.post(url(baseUrl, BffRoutes.AUTH_REFRESH)) { json(RefreshRequest(refreshToken)) } }.body()

    suspend fun me(baseUrl: String, accessToken: String): MeResponse =
        call { client.get(url(baseUrl, BffRoutes.AUTH_ME)) { bearerAuth(accessToken) } }.body()

    suspend fun changePassword(baseUrl: String, accessToken: String, request: ChangePasswordRequest) {
        call {
            client.post(url(baseUrl, BffRoutes.AUTH_PASSWORD)) {
                bearerAuth(accessToken)
                json(request)
            }
        }
    }

    suspend fun logout(baseUrl: String, accessToken: String?, refreshToken: String?) {
        call {
            client.post(url(baseUrl, BffRoutes.AUTH_LOGOUT)) {
                accessToken?.let { bearerAuth(it) }
                json(LogoutRequest(refreshToken))
            }
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = bffCall { block() }
}

internal fun url(baseUrl: String, path: String) = baseUrl.trim().trimEnd('/') + path

internal inline fun <reified T> HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/**
 * Runs one BFF request; network failures and non-2xx answers become [BffApiException].
 *
 * [acceptNon2xx] 用来放过特定的非 2xx 状态码：Artalk 的反垃圾用 `403 + {need_captcha, img_data}`
 * 表达「请先过验证码」，这个 body 是调用方必需的信息，不能在这里被吞掉。放过的响应由调用方
 * 自行判断处理（见 [ArtalkApi.post]）。
 */
internal suspend fun bffCall(
    acceptNon2xx: (Int) -> Boolean = { false },
    block: suspend () -> HttpResponse,
): HttpResponse {
    val response = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffApiException(0, "NETWORK", "无法连接服务器，请检查网络或服务器地址", e)
    }
    if (response.status.isSuccess() || acceptNon2xx(response.status.value)) return response
    val error = try {
        response.body<ApiError>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    throw BffApiException(
        response.status.value,
        error?.code ?: "HTTP_${response.status.value}",
        error?.message ?: defaultMessage(response.status.value),
        retryAfterSeconds = response.headers["Retry-After"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
    )
}

private fun defaultMessage(status: Int) = when (status) {
    401 -> "登录已过期，请重新登录"
    429 -> "操作过于频繁，请稍后再试"
    in 500..599 -> "服务器暂时不可用（HTTP $status）"
    else -> "请求失败（HTTP $status）"
}
