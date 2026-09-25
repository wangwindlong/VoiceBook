package us.wangxy.voicebook.voice.asr

import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.engine.VoiceEngine
import kotlinx.coroutines.flow.Flow

data class AsrConfig(
    val format: AudioFormat = AudioFormat.Speech16k,
    val language: String = "zh-CN",
    val hotwords: List<String> = emptyList(),
)

data class AsrResult(
    val text: String,
    val isFinal: Boolean,
    val engineId: String,
)

interface SpeechRecognizer {
    /**
     * Opens a streaming recognition session. Must return immediately: audio sent before the
     * backend is connected/ready has to be queued, never dropped.
     */
    fun startSession(config: AsrConfig): AsrSession
}

interface AsrEngine : SpeechRecognizer, VoiceEngine

interface AsrSession {
    /**
     * Partial and final hypotheses (a session may produce several finals). Single collector.
     * Completes after [finish] once the last result is delivered; fails on engine errors.
     */
    val results: Flow<AsrResult>

    /** Non-blocking; chunks are queued in order. */
    fun sendAudio(chunk: AudioChunk)

    /** No more audio: flush and deliver the final result. */
    fun finish()

    /** Abort without results. */
    fun cancel()
}
