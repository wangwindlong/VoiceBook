package us.wangxy.voicebook.voice.vad

data class SpeechDetectorConfig(
    /** Probability that counts as speech while nothing is playing. */
    val speechThreshold: Float = 0.5f,
    /** Stricter threshold while TTS is playing, to reject residual echo. */
    val playbackSpeechThreshold: Float = 0.7f,
    /** Below this, a frame counts as silence (hysteresis against [speechThreshold]). */
    val silenceThreshold: Float = 0.35f,
    /**
     * Frames quieter than this (dBFS) are never speech. Neural VADs ignore loudness, so the
     * AEC/noise-suppression residue left after playback (around -70 dBFS) can look like speech;
     * real speech on the voice-communication path is around -35..-20 dBFS.
     */
    val minSpeechLevelDb: Float = -60f,
    /** Consecutive speech needed to open an utterance (and start streaming to ASR). */
    val onsetMs: Int = 60,
    /** Voiced time needed to confirm an utterance while nothing is playing. */
    val confirmMs: Int = 120,
    /** Voiced time needed to confirm an utterance while TTS is playing (barge-in). */
    val playbackConfirmMs: Int = 250,
    /** Silence that discards an utterance that was never confirmed. */
    val falseAlarmSilenceMs: Int = 200,
    /** Trailing silence that ends a confirmed utterance. */
    val endSilenceMs: Int = 700,
    val maxUtteranceMs: Int = 30_000,
)

enum class SpeechEvent {
    /** Speech onset: open the ASR stream now (with pre-roll) so no words are lost. */
    Start,

    /** Enough voiced audio to trust that someone is really talking. */
    Confirmed,

    /** A confirmed utterance ended. */
    End,

    /** An unconfirmed onset turned out to be noise or echo. */
    FalseAlarm,
}

/**
 * Pure state machine turning per-frame VAD probabilities into utterance boundaries.
 */
class SpeechDetector(private val config: SpeechDetectorConfig = SpeechDetectorConfig()) {
    private enum class Phase { Silence, Pending, Speaking }

    private var phase = Phase.Silence
    private var speechRunMs = 0
    private var voicedMs = 0
    private var silenceRunMs = 0
    private var utteranceMs = 0

    val isInUtterance: Boolean get() = phase != Phase.Silence

    fun update(probability: Float, frameMs: Int, playbackActive: Boolean): SpeechEvent? {
        val speechThreshold = if (playbackActive) config.playbackSpeechThreshold else config.speechThreshold
        val isSpeech = probability >= speechThreshold
        val isSilence = probability < config.silenceThreshold

        when (phase) {
            Phase.Silence -> {
                speechRunMs = if (isSpeech) speechRunMs + frameMs else 0
                if (speechRunMs >= config.onsetMs) {
                    phase = Phase.Pending
                    voicedMs = speechRunMs
                    utteranceMs = speechRunMs
                    silenceRunMs = 0
                    return SpeechEvent.Start
                }
            }

            Phase.Pending -> {
                utteranceMs += frameMs
                when {
                    isSpeech -> {
                        voicedMs += frameMs
                        silenceRunMs = 0
                    }
                    isSilence -> silenceRunMs += frameMs
                }
                val needed = if (playbackActive) config.playbackConfirmMs else config.confirmMs
                if (voicedMs >= needed) {
                    phase = Phase.Speaking
                    silenceRunMs = 0
                    return SpeechEvent.Confirmed
                }
                if (silenceRunMs >= config.falseAlarmSilenceMs) {
                    reset()
                    return SpeechEvent.FalseAlarm
                }
            }

            Phase.Speaking -> {
                utteranceMs += frameMs
                silenceRunMs = if (isSilence) silenceRunMs + frameMs else 0
                if (silenceRunMs >= config.endSilenceMs || utteranceMs >= config.maxUtteranceMs) {
                    reset()
                    return SpeechEvent.End
                }
            }
        }
        return null
    }

    fun reset() {
        phase = Phase.Silence
        speechRunMs = 0
        voicedMs = 0
        silenceRunMs = 0
        utteranceMs = 0
    }
}
