package us.wangxy.voicebook.voice.routing

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.SpeechRecognizer
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.engine.NoEngineAvailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The dispatch layer for recognition: picks local or cloud per [EngineRouter] and fails over
 * mid-utterance by replaying the not-yet-finalized audio into the next engine.
 */
class RoutingSpeechRecognizer(
    private val router: EngineRouter<AsrEngine>,
    private val maxReplayMs: Int = 30_000,
) : SpeechRecognizer {
    override fun startSession(config: AsrConfig): AsrSession =
        FailoverAsrSession(router, config, config.format.samplesFor(maxReplayMs)).also { it.start() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class FailoverAsrSession(
    private val router: EngineRouter<AsrEngine>,
    private val config: AsrConfig,
    private val maxReplaySamples: Int,
) : AsrSession {
    // Single-threaded so the replay history is only touched from one coroutine at a time.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val audioIn = Channel<AudioChunk>(Channel.UNLIMITED)
    private val output = Channel<AsrResult>(Channel.UNLIMITED)

    /** Audio since the last final result; replayed into the fallback engine on failure. */
    private val history = ArrayDeque<AudioChunk>()
    private var historySamples = 0

    override val results: Flow<AsrResult> = output.receiveAsFlow()

    fun start() {
        scope.launch {
            val candidates = router.candidates()
            if (candidates.isEmpty()) {
                output.close(NoEngineAvailableException("ASR"))
                return@launch
            }
            var lastError: Throwable? = null
            for (engine in candidates) {
                val session = engine.startSession(config)
                history.forEach(session::sendAudio)
                try {
                    coroutineScope {
                        val pump = launch {
                            for (chunk in audioIn) {
                                remember(chunk)
                                session.sendAudio(chunk)
                            }
                            session.finish()
                        }
                        session.results.collect { result ->
                            if (result.isFinal) clearHistory()
                            output.send(result)
                        }
                        pump.cancel()
                    }
                    output.close()
                    return@launch
                } catch (e: CancellationException) {
                    session.cancel()
                    throw e
                } catch (e: Throwable) {
                    session.cancel()
                    lastError = e
                }
            }
            output.close(lastError)
        }
    }

    private fun remember(chunk: AudioChunk) {
        history.addLast(chunk)
        historySamples += chunk.samples.size
        while (historySamples > maxReplaySamples && history.size > 1) {
            historySamples -= history.removeFirst().samples.size
        }
    }

    private fun clearHistory() {
        history.clear()
        historySamples = 0
    }

    override fun sendAudio(chunk: AudioChunk) {
        audioIn.trySend(chunk)
    }

    override fun finish() {
        audioIn.close()
    }

    override fun cancel() {
        audioIn.cancel()
        output.close()
        scope.cancel()
    }
}
