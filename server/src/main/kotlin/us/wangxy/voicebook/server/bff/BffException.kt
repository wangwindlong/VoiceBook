package us.wangxy.voicebook.server.bff

import io.ktor.http.HttpStatusCode

/** Rendered by StatusPages as `ApiError(code, message)` with [status]. */
class BffException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun badRequest(message: String, code: String = "BAD_REQUEST") =
            BffException(HttpStatusCode.BadRequest, code, message)

        fun notFound(message: String) = BffException(HttpStatusCode.NotFound, "NOT_FOUND", message)

        fun upstream(component: String, message: String, cause: Throwable? = null) =
            BffException(HttpStatusCode.BadGateway, "UPSTREAM_${component.uppercase()}", message, cause)
    }
}
