package us.wangxy.voicebook.bff

/**
 * The BFF connection details a feature module needs but cannot own.
 *
 * [us.wangxy.voicebook.auth.AuthController] is the only implementation: it knows the configured
 * server URL and holds the token, while feature modules such as `:feature:rss` must not depend on
 * `:feature:auth` (that would invert the module graph). They depend on this interface instead and
 * let Koin inject the controller.
 */
interface BffSession {
    /** Base URL of the BFF, e.g. `https://nas.wangyl.work:8462` (no trailing slash). */
    fun baseUrl(): String

    /**
     * A currently valid access token, refreshing it first when it is close to expiry.
     * Returns null when nobody is signed in.
     */
    suspend fun accessToken(): String?
}
