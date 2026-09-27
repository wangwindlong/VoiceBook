package us.wangxy.voicebook.audio

import kotlinx.coroutines.flow.StateFlow

/** One playable RSS attachment. */
data class AudioTrack(
    val url: String,
    val title: String,
    val source: String,
    val imageUrl: String? = null,
    /** Post id for progress persistence; null for non-article playback. */
    val postId: String? = null,
)

data class AudioState(
    val track: AudioTrack? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

/**
 * Platform audio playback for RSS enclosures. v1 semantics (documented):
 * single track at a time; desktop resumes restart the stream (no seek) while
 * Android/iOS/web support position polling and seeking.
 */
interface AudioPlayer {
    val state: StateFlow<AudioState>

    fun load(track: AudioTrack, startMs: Long = 0)
    fun toggle()
    fun stop()
    fun seekTo(fraction: Float)
}

expect fun createAudioPlayer(): AudioPlayer
