package us.wangxy.voicebook.voice.duplex

import us.wangxy.voicebook.voice.audio.AudioChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.log10
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Observes the inside of a [DuplexVoiceSession]. All callbacks run on the session's serial
 * dispatcher, in order, and must be fast.
 */
interface DuplexMonitor {
    /** Every microphone frame after echo cancellation, as seen by the VAD. */
    fun onMicFrame(frame: AudioChunk, vadProbability: Float, playbackActive: Boolean) {}

    /** Synthesized audio handed to the player (the echo reference). */
    fun onPlayback(chunk: AudioChunk) {}

    /** Playback ended or was cut; audio from [onPlayback] not yet heard never will be. */
    fun onPlaybackStopped() {}

    fun onEvent(event: VoiceEvent) {}
}

data class DuplexReport(
    /** Mean mic level while nothing is playing (room noise floor), dBFS. */
    val idleLevelDb: Float? = null,
    /** Mean mic level while TTS is audible, dBFS. */
    val playbackLevelDb: Float? = null,
    val playbackPeakDb: Float? = null,
    /** Share of playback frames whose VAD probability exceeds the playback threshold. */
    val playbackVadHitRatio: Float? = null,
    val playbackMaxVad: Float = 0f,
    val playbackSeconds: Float = 0f,
    val onsetsDuringPlayback: Int = 0,
    val discardedDuringPlayback: Int = 0,
    val echoSuppressed: Int = 0,
    val backchannels: Int = 0,
    val bargeIns: Int = 0,
    val finals: Int = 0,
    /** Speech onset to TTS stop for the last barge-in. */
    val lastBargeInLatencyMs: Long? = null,
) {
    /**
     * How much louder the mic gets while TTS plays. With working AEC and a silent user this stays
     * within a few dB; 15 dB or more means the TTS is leaking into the recognizer's input.
     */
    val echoLeakDb: Float? get() = if (idleLevelDb != null && playbackLevelDb != null) playbackLevelDb - idleLevelDb else null
}

/**
 * Aggregates echo/interference metrics for verifying full duplex on a real device:
 * speak a long prompt without talking and check that [DuplexReport.echoLeakDb] stays low,
 * [DuplexReport.playbackVadHitRatio] stays near 0, and no barge-in / final appears.
 */
class DuplexProbe(private val playbackVadThreshold: Float = 0.7f) : DuplexMonitor {
    private val _report = MutableStateFlow(DuplexReport())
    val report: StateFlow<DuplexReport> = _report.asStateFlow()

    private var idlePower = 0.0
    private var idleFrames = 0
    private var playbackPower = 0.0
    private var playbackPeak = 0.0
    private var playbackFrames = 0
    private var playbackVadHits = 0
    private var playbackMs = 0L
    private var framesSincePublish = 0
    private val playbackUtterances = HashSet<Long>()
    private val onsetMarks = HashMap<Long, TimeMark>()

    @Volatile
    private var resetRequested = false

    /** Safe from any thread; applied on the session thread with the next callback. */
    fun reset() {
        resetRequested = true
        _report.value = DuplexReport()
    }

    private fun applyPendingReset() {
        if (!resetRequested) return
        resetRequested = false
        idlePower = 0.0
        idleFrames = 0
        playbackPower = 0.0
        playbackPeak = 0.0
        playbackFrames = 0
        playbackVadHits = 0
        playbackMs = 0
        playbackUtterances.clear()
        onsetMarks.clear()
        _report.value = DuplexReport()
    }

    override fun onMicFrame(frame: AudioChunk, vadProbability: Float, playbackActive: Boolean) {
        applyPendingReset()
        val power = meanPower(frame.samples)
        if (playbackActive) {
            playbackPower += power
            if (power > playbackPeak) playbackPeak = power
            playbackFrames++
            playbackMs += frame.durationMs
            if (vadProbability >= playbackVadThreshold) playbackVadHits++
            if (vadProbability > _report.value.playbackMaxVad) {
                _report.value = _report.value.copy(playbackMaxVad = vadProbability)
            }
        } else {
            idlePower += power
            idleFrames++
        }
        if (++framesSincePublish >= 10) {
            framesSincePublish = 0
            publishLevels()
        }
    }

    override fun onEvent(event: VoiceEvent) {
        applyPendingReset()
        val r = _report.value
        _report.value = when (event) {
            is VoiceEvent.SpeechStarted -> {
                onsetMarks[event.utteranceId] = TimeSource.Monotonic.markNow()
                if (event.duringPlayback) {
                    playbackUtterances += event.utteranceId
                    r.copy(onsetsDuringPlayback = r.onsetsDuringPlayback + 1)
                } else {
                    r
                }
            }
            is VoiceEvent.SpeechEnded ->
                if (event.discarded && event.utteranceId in playbackUtterances) {
                    r.copy(discardedDuringPlayback = r.discardedDuringPlayback + 1)
                } else {
                    r
                }
            is VoiceEvent.EchoSuppressed -> r.copy(echoSuppressed = r.echoSuppressed + 1)
            is VoiceEvent.Backchannel -> r.copy(backchannels = r.backchannels + 1)
            is VoiceEvent.BargeIn -> r.copy(
                bargeIns = r.bargeIns + 1,
                lastBargeInLatencyMs = onsetMarks[event.utteranceId]?.elapsedNow()?.inWholeMilliseconds,
            )
            is VoiceEvent.Final -> r.copy(finals = r.finals + 1)
            else -> r
        }
    }

    private fun publishLevels() {
        _report.value = _report.value.copy(
            idleLevelDb = if (idleFrames > 0) toDb(idlePower / idleFrames) else null,
            playbackLevelDb = if (playbackFrames > 0) toDb(playbackPower / playbackFrames) else null,
            playbackPeakDb = if (playbackFrames > 0) toDb(playbackPeak) else null,
            playbackVadHitRatio = if (playbackFrames > 0) playbackVadHits.toFloat() / playbackFrames else null,
            playbackSeconds = playbackMs / 1000f,
        )
    }

    private fun meanPower(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sum / samples.size
    }

    private fun toDb(power: Double): Float = (10 * log10(power + 1e-12)).toFloat()
}
