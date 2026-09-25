package us.wangxy.voicebook.voice.asr

import us.wangxy.voicebook.voice.audio.AudioChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Base for engine sessions: queues audio in an unbounded channel so nothing is lost while the
 * backend connects, and runs the engine loop in its own scope. Call [start] after construction.
 */
abstract class BufferedAsrSession(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AsrSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val audio = Channel<AudioChunk>(Channel.UNLIMITED)
    private val output = Channel<AsrResult>(Channel.UNLIMITED)

    final override val results: Flow<AsrResult> = output.receiveAsFlow()

    /** Consume [audio] until it is closed (finish) and report hypotheses through [emit]. */
    protected abstract suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit)

    fun start(): AsrSession {
        scope.launch {
            try {
                run(audio) { output.send(it) }
                output.close()
            } catch (e: CancellationException) {
                output.close()
                throw e
            } catch (e: Throwable) {
                output.close(e)
            }
        }
        return this
    }

    final override fun sendAudio(chunk: AudioChunk) {
        audio.trySend(chunk)
    }

    final override fun finish() {
        audio.close()
    }

    final override fun cancel() {
        audio.cancel()
        output.close()
        scope.cancel()
    }
}
