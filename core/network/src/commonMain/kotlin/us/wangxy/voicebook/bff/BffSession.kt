package us.wangxy.voicebook.bff

import kotlinx.coroutines.flow.StateFlow

/**
 * The BFF connection details a feature module needs but cannot own.
 *
 * [us.wangxy.voicebook.auth.AuthController] is the only implementation: it knows the configured
 * server URL and holds the token, while feature modules such as `:feature:rss` must not depend on
 * `:feature:auth` (that would invert the module graph). They depend on this interface instead and
 * let Koin inject the controller.
 *
 * Signed in means every backend (calibre, Miniflux, Artalk) is reached through the BFF; signed out
 * means each feature falls back to its own directly configured server.
 */
interface BffSession {
    /** Base URL of the BFF, e.g. `https://nas.wangyl.work:8462` (no trailing slash). */
    fun baseUrl(): String

    /** Username of the signed-in account, null when signed out; emits on login / logout. */
    val signedInUser: StateFlow<String?>

    /**
     * A currently valid access token, refreshing it first when it is close to expiry.
     * Returns null when nobody is signed in.
     */
    suspend fun accessToken(): String?

    /** The stored access token without refreshing, for non-suspending callers (image request headers). */
    fun currentAccessToken(): String?
}

val BffSession.isSignedIn: Boolean get() = signedInUser.value != null
