package us.wangxy.voicebook.voice.listen

/** Transport commands arriving from outside the app UI: notification, lock screen, headset buttons. */
interface MediaTransport {
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun stop()
}

data class MediaNowPlaying(
    val title: String,
    val subtitle: String,
    val playing: Boolean,
)

/**
 * Platform side of long-form playback (audiobooks): audio focus, staying alive in the background
 * (an Android foreground service) and the system media controls. All methods may be called from
 * any thread.
 */
interface MediaPlaybackHost {
    /**
     * Requests audio focus and publishes the media session; idempotent while active. Returns false
     * when focus is denied (e.g. during a phone call), in which case playback must not start.
     */
    fun activate(transport: MediaTransport): Boolean

    fun update(nowPlaying: MediaNowPlaying)

    /**
     * A user-facing reason why playback would be inaudible right now (media volume near zero,
     * output routed to a virtual device), or null when the output looks fine.
     */
    fun outputWarning(): String? = null

    /** Abandons audio focus and removes the session and its notification. */
    fun deactivate()
}

/** Platforms without system media integration (desktop, web, iOS for now). */
object NoMediaPlaybackHost : MediaPlaybackHost {
    override fun activate(transport: MediaTransport): Boolean = true
    override fun update(nowPlaying: MediaNowPlaying) = Unit
    override fun deactivate() = Unit
}
