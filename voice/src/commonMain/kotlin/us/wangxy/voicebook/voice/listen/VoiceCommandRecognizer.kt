package us.wangxy.voicebook.voice.listen

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.SpeechRecognizer
import us.wangxy.voicebook.voice.audio.AudioCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot push-to-talk recognition: opens the microphone only for the duration of one short
 * command, so long-form playback never keeps the mic, VAD and ASR running.
 */
class VoiceCommandRecognizer(
    private val capture: AudioCapture,
    private val recognizer: SpeechRecognizer,
) {
    /** Returns the recognized text (possibly empty) after the first final result or [maxListenMs]. */
    suspend fun listenOnce(
        hotwords: List<String> = emptyList(),
        maxListenMs: Long = 5_000,
        flushTimeoutMs: Long = 2_000,
    ): String = coroutineScope {
        val session = recognizer.startSession(AsrConfig(format = capture.format, hotwords = hotwords))
        val latest = MutableStateFlow("")
        val final = CompletableDeferred<String>()
        val reader = launch {
            try {
                session.results.collect { result ->
                    val text = result.text.trim()
                    if (text.isNotEmpty()) latest.value = text
                    if (result.isFinal && text.isNotEmpty()) final.complete(text)
                }
            } finally {
                final.complete(latest.value)
            }
        }
        val mic = launch { capture.frames().collect(session::sendAudio) }
        try {
            val early = withTimeoutOrNull(maxListenMs) { final.await() }
            mic.cancelAndJoin()
            if (early != null) {
                session.cancel()
                return@coroutineScope early
            }
            session.finish()
            withTimeoutOrNull(flushTimeoutMs) { final.await() } ?: latest.value
        } finally {
            mic.cancel()
            reader.cancel()
        }
    }
}
