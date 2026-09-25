package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.BufferedAsrSession
import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.engine.EngineKind
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsRequest
import us.wangxy.voicebook.voice.vad.VoiceActivityDetector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.Volatile

/** Sample values used as markers for where a frame "came from". */
object Marker {
    const val SILENCE: Short = 0
    const val USER: Short = 1000
    const val USER_FIRST_WORD: Short = 1111
    const val ECHO: Short = 2000
    const val FILLER: Short = 3000

    /** AEC/NS residue after playback: about -64 dBFS, yet speech-like to a neural VAD. */
    const val RESIDUE: Short = 20
}

fun frame(value: Short, ms: Int = 20) = AudioChunk(ShortArray(AudioFormat.Speech16k.samplesFor(ms)) { value }, AudioFormat.Speech16k)

class FakeCapture : AudioCapture {
    val mic = Channel<AudioChunk>(Channel.UNLIMITED)
    override val format = AudioFormat.Speech16k
    override val hasPlatformEchoCancellation = true
    override fun frames(): Flow<AudioChunk> = mic.receiveAsFlow()

    fun send(value: Short, ms: Int) = repeat(ms / 20) { mic.trySend(frame(value)) }
}

object MarkerVad : VoiceActivityDetector {
    override fun process(chunk: AudioChunk): Float = if (chunk.samples.any { it != Marker.SILENCE }) 1f else 0f
    override fun reset() = Unit
}

class FakePlayer : AudioPlayer {
    @Volatile var chunksPlayed = 0
    @Volatile var stoppedImmediately = false
    @Volatile var everDucked = false
    @Volatile var duckedNow = false

    override suspend fun play(chunks: Flow<AudioChunk>) {
        chunks.collect {
            chunksPlayed++
            delay(it.durationMs.toLong())
        }
    }

    override fun stopImmediately() {
        stoppedImmediately = true
    }

    override fun setDucked(ducked: Boolean) {
        duckedNow = ducked
        if (ducked) everDucked = true
    }
}

class FakeSynthesizer(private val chunks: Int) : SpeechSynthesizer {
    override fun synthesize(request: TtsRequest): Flow<AudioChunk> = flow {
        repeat(chunks) { emit(frame(Marker.ECHO)) }
    }
}

/**
 * Recognizes [userText] once it hears user-marked audio, and "hears" [echoText] when it only gets
 * echo-marked audio, like a real recognizer fed with leaked TTS.
 */
class FakeRecognizer(
    private val userText: String,
    private val echoText: String,
    override val id: String = "fake",
    override val kind: EngineKind = EngineKind.Local,
) : AsrEngine {
    val sessions = mutableListOf<Session>()

    override suspend fun isAvailable() = true

    override fun startSession(config: AsrConfig): AsrSession = Session().also {
        sessions += it
        it.start()
    }

    inner class Session : BufferedAsrSession() {
        val received = mutableListOf<AudioChunk>()

        fun heard(value: Short) = received.any { chunk -> chunk.samples.any { it == value } }

        private fun text() = when {
            heard(Marker.USER) || heard(Marker.USER_FIRST_WORD) -> userText
            heard(Marker.FILLER) -> "嗯"
            heard(Marker.ECHO) -> echoText
            heard(Marker.RESIDUE) -> "好的"
            else -> ""
        }

        override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
            var last = ""
            for (chunk in audio) {
                received += chunk
                val t = text()
                if (t.isNotEmpty() && t != last) {
                    emit(AsrResult(t, isFinal = false, engineId = id))
                    last = t
                }
            }
            val t = text()
            if (t.isNotEmpty()) emit(AsrResult(t, isFinal = true, engineId = id))
        }
    }
}
