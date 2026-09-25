package us.wangxy.voicebook.voice.tts

import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.engine.VoiceEngine
import kotlinx.coroutines.flow.Flow

data class TtsRequest(
    val text: String,
    val voice: String? = null,
    val speed: Float = 1f,
    val language: String = "zh-CN",
)

interface SpeechSynthesizer {
    /**
     * Cold flow of synthesized PCM, emitted as soon as each piece is ready (streaming) so playback
     * can start before synthesis completes. Cancelling the collection aborts synthesis.
     */
    fun synthesize(request: TtsRequest): Flow<AudioChunk>
}

interface TtsEngine : SpeechSynthesizer, VoiceEngine
