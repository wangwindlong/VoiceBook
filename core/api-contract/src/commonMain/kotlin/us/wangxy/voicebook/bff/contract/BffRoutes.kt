package us.wangxy.voicebook.bff.contract

/** Paths exposed by the BFF. The app must not talk to Miniflux / Artalk / calibre directly. */
object BffRoutes {
    const val HEALTH = "/healthz"

    const val AUTH_CONFIG = "/api/auth/config"
    const val AUTH_LOGIN = "/api/auth/login"
    const val AUTH_REFRESH = "/api/auth/refresh"
    const val AUTH_LOGOUT = "/api/auth/logout"
    const val AUTH_REGISTER = "/api/auth/register"
    const val AUTH_PASSWORD = "/api/auth/password"
    const val AUTH_ME = "/api/auth/me"
    const val AUTH_TOKEN_EXCHANGE = "/api/auth/token/exchange"

    /** Prefix of the Miniflux pass-through; `/api/miniflux/entries` maps to Miniflux `/v1/entries`. */
    const val MINIFLUX = "/api/miniflux"

    const val CALIBRE_BOOKS = "/api/calibre/books"
    const val CALIBRE_PROGRESS = "/api/calibre/progress"
    const val CALIBRE_ACTIVATE = "/api/calibre/activate"

    const val ARTALK_COMMENTS = "/api/artalk/comments"

    fun calibreBook(id: Long) = "$CALIBRE_BOOKS/$id"
    fun calibreCover(id: Long) = "$CALIBRE_BOOKS/$id/cover"
    fun calibreFile(id: Long, format: String) = "$CALIBRE_BOOKS/$id/file/${format.uppercase()}"
    fun calibreProgress(bookId: Long) = "$CALIBRE_PROGRESS/$bookId"
    fun minifluxMarkRead(entryId: Long) = "$MINIFLUX/entries/$entryId/read"
}

object BffComponents {
    const val MINIFLUX = "miniflux"
    const val CALIBRE = "calibre"
    const val ARTALK = "artalk"
}
