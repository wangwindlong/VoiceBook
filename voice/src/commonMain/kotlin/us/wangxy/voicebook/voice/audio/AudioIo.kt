package us.wangxy.voicebook.voice.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AudioCapture {
    val format: AudioFormat

    /**
     * True when the OS/hardware removes our own playback from the microphone signal
     * (e.g. Android VOICE_COMMUNICATION + AcousticEchoCanceler, iOS voice processing IO).
     */
    val hasPlatformEchoCancellation: Boolean

    /**
     * Cold flow of microphone frames. Collecting opens the microphone, cancelling releases it.
     * Implementations must keep capturing while [AudioPlayer] is playing (full duplex).
     */
    fun frames(): Flow<AudioChunk>
}

interface AudioPlayer {
    /**
     * Plays [chunks] in order and suspends until the audio has actually been rendered.
     * Cancellation must silence the output immediately and discard anything still buffered.
     */
    suspend fun play(chunks: Flow<AudioChunk>)

    /** Silences current playback right away; safe to call from any thread. */
    fun stopImmediately()

    /** Lowers (or restores) the volume of the current playback without stopping it. */
    fun setDucked(ducked: Boolean)
}

/**
 * Software acoustic echo cancellation hook, applied on top of whatever the platform already does.
 * Plug in WebRTC APM / Speex here when the platform AEC is missing or too weak.
 */
interface EchoCanceller {
    /** Far-end reference: audio handed to the player, in playback order. */
    fun onPlayback(chunk: AudioChunk)

    /** Returns the near-end microphone chunk with the far-end echo removed. */
    fun process(mic: AudioChunk): AudioChunk

    fun reset()
}

object PassThroughEchoCanceller : EchoCanceller {
    override fun onPlayback(chunk: AudioChunk) = Unit
    override fun process(mic: AudioChunk): AudioChunk = mic
    override fun reset() = Unit
}

class UnsupportedAudioCapture(private val platform: String) : AudioCapture {
    override val format: AudioFormat = AudioFormat.Speech16k
    override val hasPlatformEchoCancellation: Boolean = false
    override fun frames(): Flow<AudioChunk> = flow {
        throw UnsupportedOperationException("Audio capture is not implemented on $platform yet")
    }
}

class UnsupportedAudioPlayer(private val platform: String) : AudioPlayer {
    override suspend fun play(chunks: Flow<AudioChunk>) {
        throw UnsupportedOperationException("Audio playback is not implemented on $platform yet")
    }

    override fun stopImmediately() = Unit
    override fun setDucked(ducked: Boolean) = Unit
}
