@file:OptIn(ExperimentalForeignApi::class)

package us.wangxy.voicebook.voice.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.Foundation.NSRecursiveLock
import kotlin.coroutines.resume

/**
 * One AVAudioEngine shared by capture and playback: voice processing (Apple's AEC + noise
 * suppression + AGC, the counterpart of Android's voice-communication path) only cancels audio
 * that is played through the same engine. With [AudioProcessingSettings.systemAec] the session
 * runs in `playAndRecord` / `voiceChat` mode and voice processing is enabled on the input node;
 * otherwise the raw microphone is used. Settings apply the next time the engine starts.
 */
internal class IosAudioEngine(private val settings: AudioProcessingSettings) {
    val engine = AVAudioEngine()
    val player = AVAudioPlayerNode()
    val playerFormat = AVAudioFormat(standardFormatWithSampleRate = PlayerSampleRate.toDouble(), channels = 1u)

    private val lock = NSRecursiveLock()
    private var users = 0
    private var inputTapped = false
    private var voiceProcessing = false

    /** Receives microphone buffers (hardware rate, float) on the audio thread while tapped. */
    @kotlin.concurrent.Volatile
    var micListener: ((AVAudioPCMBuffer) -> Unit)? = null

    init {
        engine.attachNode(player)
    }

    /** Whether capture and playback currently run through voice processing. */
    val voiceProcessingActive: Boolean
        get() = locked { if (users > 0) voiceProcessing else settings.systemAec.value }

    /** Asks for microphone access if it hasn't been decided yet. */
    suspend fun requestRecordPermission(): Boolean {
        val session = AVAudioSession.sharedInstance()
        return when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> true
            AVAudioSessionRecordPermissionUndetermined -> suspendCancellableCoroutine { cont ->
                session.requestRecordPermission { granted -> cont.resume(granted) }
            }
            else -> false
        }
    }

    /**
     * [needsInput]: the caller needs the microphone. The mic is tapped whenever permission is
     * granted, so this only restarts the engine if it was started before permission was given.
     */
    fun acquire(needsInput: Boolean) = locked {
        val restart = users > 0 && needsInput && !inputTapped
        if (restart) stopEngine()
        if (users == 0 || restart) {
            startEngine()
            if (restart) player.play()
        }
        users++
    }

    fun release() = locked {
        if (users > 0 && --users == 0) {
            stopEngine()
            AVAudioSession.sharedInstance().setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null,
            )
        }
    }

    private fun startEngine() {
        val session = AVAudioSession.sharedInstance()
        val micAllowed = session.recordPermission == AVAudioSessionRecordPermissionGranted
        val aec = settings.systemAec.value && micAllowed
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = if (aec) AVAudioSessionModeVoiceChat else AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)

        val input = engine.inputNode
        // Must be set while the engine is stopped; it reconfigures the I/O unit and node formats.
        if (micAllowed) input.setVoiceProcessingEnabled(aec, error = null)
        voiceProcessing = aec && input.voiceProcessingEnabled
        engine.disconnectNodeOutput(player)
        engine.connect(player, to = engine.mainMixerNode, format = playerFormat)
        if (micAllowed) {
            input.installTapOnBus(0u, bufferSize = 1024u, format = null) { buffer, _ ->
                if (buffer != null) micListener?.invoke(buffer)
            }
            inputTapped = true
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            if (inputTapped) input.removeTapOnBus(0u)
            inputTapped = false
            error("AVAudioEngine failed to start")
        }
    }

    private fun stopEngine() {
        player.stop()
        engine.stop()
        if (inputTapped) engine.inputNode.removeTapOnBus(0u)
        inputTapped = false
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val PlayerSampleRate = 24_000
    }
}
