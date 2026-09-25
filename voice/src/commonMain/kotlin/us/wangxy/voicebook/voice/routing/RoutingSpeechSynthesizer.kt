package us.wangxy.voicebook.voice.routing

import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.engine.NoEngineAvailableException
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsEngine
import us.wangxy.voicebook.voice.tts.TtsRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * The dispatch layer for synthesis. Falls back to the next engine only if the failing one has
 * not produced any audio yet; otherwise the listener would hear the beginning twice.
 */
class RoutingSpeechSynthesizer(
    private val router: EngineRouter<TtsEngine>,
) : SpeechSynthesizer {
    override fun synthesize(request: TtsRequest): Flow<AudioChunk> = flow {
        val candidates = router.candidates()
        if (candidates.isEmpty()) throw NoEngineAvailableException("TTS")
        var lastError: Throwable? = null
        for (engine in candidates) {
            var emitted = false
            var upstreamError: Throwable? = null
            engine.synthesize(request)
                .catch { upstreamError = it }
                .collect {
                    emitted = true
                    emit(it)
                }
            val error = upstreamError ?: return@flow
            if (emitted) throw error
            lastError = error
        }
        throw lastError!!
    }
}
