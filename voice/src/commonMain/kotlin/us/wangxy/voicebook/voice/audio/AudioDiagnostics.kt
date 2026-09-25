package us.wangxy.voicebook.voice.audio

import us.wangxy.voicebook.voice.duplex.DuplexMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** Platform audio-processing switches, read when capture/playback (re)starts. */
class AudioProcessingSettings(systemAec: Boolean = true) {
    /**
     * Android: VOICE_COMMUNICATION source + AcousticEchoCanceler + MODE_IN_COMMUNICATION playback.
     * Turning it off (plain MIC + media playback) is useful to A/B how much echo the platform removes.
     */
    val systemAec = MutableStateFlow(systemAec)
}

/**
 * Records what the session hears for offline analysis. Attach it with
 * `DuplexVoiceSession.addMonitor`; the Android implementation writes a stereo WAV with the
 * processed microphone on the left and the TTS reference on the right.
 */
interface DebugAudioRecorder : DuplexMonitor {
    /** Starts a new recording and returns where it is written. */
    fun start(): String

    fun stop()
}
