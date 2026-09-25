package us.wangxy.voicebook.voice.duplex

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.vad.SpeechDetectorConfig

enum class BargeInConfirmation {
    /** Interrupt as soon as the VAD confirms speech. Fastest; relies on a good AEC. */
    Vad,

    /**
     * Additionally require recognized text that the [EchoTextFilter] attributes to the user and
     * that is not a backchannel ("嗯", "哦"). Slightly slower (one partial result), but immune to
     * leaked TTS audio and to listening noises.
     */
    VadAndUserText,
}

enum class DuckPolicy {
    Never,

    /** Duck on the VAD onset. Earliest feedback, but also ducks for "嗯"/"哦" and echo blips. */
    OnSpeechOnset,

    /** Duck once recognized text is attributed to the user and is not a backchannel. */
    OnUserText,
}

data class DuplexConfig(
    val asr: AsrConfig = AsrConfig(),
    val detector: SpeechDetectorConfig = SpeechDetectorConfig(),
    /** Audio before the VAD onset that is prepended to every utterance, so the first word isn't cut. */
    val preRollMs: Int = 800,
    val bargeInEnabled: Boolean = true,
    val bargeInConfirmation: BargeInConfirmation = BargeInConfirmation.VadAndUserText,
    /** When to lower the TTS volume before the barge-in is confirmed. */
    val duckPolicy: DuckPolicy = DuckPolicy.OnUserText,
    /** After playback stops, residual echo can still reach the mic for this long. */
    val echoTailMs: Int = 400,
    /**
     * For this long after playback an unconfirmed blip is treated as echo residue and dropped
     * instead of being sent to the recognizer (which would turn it into words). A real answer
     * is long enough to be confirmed and is unaffected.
     */
    val echoGuardMs: Int = 1_500,
    val echoText: EchoTextFilterConfig = EchoTextFilterConfig(),
    val backchannel: BackchannelConfig = BackchannelConfig(),
)

data class DuplexState(
    val listening: Boolean = false,
    val userSpeaking: Boolean = false,
    val speaking: Boolean = false,
)

sealed interface SpeakResult {
    data object Completed : SpeakResult

    /** [bargeInUtteranceId] is set when the user interrupted by voice. */
    data class Interrupted(val bargeInUtteranceId: Long?) : SpeakResult

    data class Failed(val cause: Throwable) : SpeakResult
}

sealed interface VoiceEvent {
    data class SpeechStarted(val utteranceId: Long, val duringPlayback: Boolean) : VoiceEvent

    /** [discarded] is true when the onset turned out to be noise/echo. */
    data class SpeechEnded(val utteranceId: Long, val discarded: Boolean) : VoiceEvent

    data class Partial(val utteranceId: Long, val text: String) : VoiceEvent
    data class Final(val utteranceId: Long, val text: String) : VoiceEvent

    /** The user talked over the TTS and playback was stopped. */
    data class BargeIn(val utteranceId: Long, val turnId: Long) : VoiceEvent

    /** A hypothesis that was attributed to our own TTS and therefore dropped. */
    data class EchoSuppressed(val utteranceId: Long, val text: String) : VoiceEvent

    /** "嗯"/"哦" while the assistant was speaking: reported, but playback is left untouched. */
    data class Backchannel(val utteranceId: Long, val text: String) : VoiceEvent

    data class SpeakStarted(val turnId: Long, val text: String) : VoiceEvent
    data class SpeakFinished(val turnId: Long, val result: SpeakResult) : VoiceEvent

    data class Error(val source: String, val cause: Throwable) : VoiceEvent
}
