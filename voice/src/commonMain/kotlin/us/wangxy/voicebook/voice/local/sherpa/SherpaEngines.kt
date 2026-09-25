package us.wangxy.voicebook.voice.local.sherpa

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.BufferedAsrSession
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.toFloatPcm
import us.wangxy.voicebook.voice.audio.toShortPcm
import us.wangxy.voicebook.voice.engine.EngineKind
import us.wangxy.voicebook.voice.engine.VoiceEngineException
import us.wangxy.voicebook.voice.tts.TtsEngine
import us.wangxy.voicebook.voice.tts.TtsRequest
import us.wangxy.voicebook.voice.vad.EnergyVad
import us.wangxy.voicebook.voice.vad.VoiceActivityDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/*
 * Engines take a backend provider rather than a backend, so models installed after startup
 * (first-run download) are used without rebuilding the object graph.
 */

/** Silence tail fed at [SherpaAsrEngine] session end: a 480ms-chunk transducer emits a chunk's
 *  tokens only when the following chunk decodes, so the tail must span two chunk boundaries. */
private const val FlushTailSeconds = 1.0f

/** Uses a streaming model when installed, otherwise the offline one; chosen per session. */
class SherpaLocalAsrEngine(
    private val backends: () -> SherpaBackends,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AsrEngine {
    private val streaming = SherpaAsrEngine({ backends().recognizer }, dispatcher)
    private val offline = SherpaOfflineAsrEngine({ backends().offlineRecognizer }, dispatcher = dispatcher)

    override val id: String = "sherpa-onnx-asr"
    override val kind: EngineKind = EngineKind.Local

    override suspend fun isAvailable(): Boolean = streaming.isAvailable() || offline.isAvailable()

    override fun startSession(config: AsrConfig): AsrSession =
        if (backends().recognizer != null) streaming.startSession(config) else offline.startSession(config)
}

class SherpaAsrEngine(
    private val backend: () -> SherpaOnlineRecognizerBackend?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AsrEngine {
    override val id: String = "sherpa-onnx-streaming-asr"
    override val kind: EngineKind = EngineKind.Local

    override suspend fun isAvailable(): Boolean = backend() != null

    override fun startSession(config: AsrConfig): AsrSession {
        val backend = backend() ?: throw VoiceEngineException("sherpa-onnx recognizer is not configured")
        return Session(backend, config).also { it.start() }
    }

    private inner class Session(
        private val backend: SherpaOnlineRecognizerBackend,
        private val config: AsrConfig,
    ) : BufferedAsrSession(dispatcher) {
        override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
            val stream = backend.createStream(config.hotwords)
            try {
                var lastPartial = ""
                var sampleRate = config.format.sampleRate
                for (chunk in audio) {
                    sampleRate = chunk.format.sampleRate
                    stream.acceptWaveform(chunk.samples.toFloatPcm(), chunk.format.sampleRate)
                    while (stream.isReady()) stream.decode()
                    val text = stream.result().trim()
                    if (stream.isEndpoint()) {
                        if (text.isNotEmpty()) emit(AsrResult(text, isFinal = true, engineId = id))
                        stream.reset()
                        lastPartial = ""
                    } else if (text.isNotEmpty() && text != lastPartial) {
                        emit(AsrResult(text, isFinal = false, engineId = id))
                        lastPartial = text
                    }
                }
                // Chunked transducers emit a chunk's tokens only when the NEXT chunk arrives:
                // a session that stops right after the last word would drop its tail. A live
                // mic always trails with silence, so feed a synthetic tail before finishing.
                feedSilence(stream, sampleRate)
                stream.inputFinished()
                while (stream.isReady()) stream.decode()
                val text = stream.result().trim().ifEmpty { lastPartial }
                if (text.isNotEmpty()) emit(AsrResult(text, isFinal = true, engineId = id))
            } finally {
                stream.release()
            }
        }

        /** Keep the tail under the endpoint threshold (0.8 s) so no partial gets reset away. */
        private fun feedSilence(stream: SherpaOnlineStream, sampleRate: Int) {
            val silence = FloatArray(sampleRate / 10)
            repeat((FlushTailSeconds * 10).toInt()) {
                stream.acceptWaveform(silence, sampleRate)
                while (stream.isReady()) stream.decode()
            }
        }
    }
}

class SherpaTtsEngine(
    private val backend: () -> SherpaTtsBackend?,
    private val speakerId: Int = 0,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TtsEngine {
    override val id: String = "sherpa-onnx-tts"
    override val kind: EngineKind = EngineKind.Local

    override suspend fun isAvailable(): Boolean = backend() != null

    override fun synthesize(request: TtsRequest): Flow<AudioChunk> = channelFlow {
        val backend = backend() ?: throw VoiceEngineException("sherpa-onnx TTS is not configured")
        // Everything touching the backend (including sampleRate, which may load the model) runs on
        // [dispatcher]: this flow is collected on the session's serial mic-processing thread.
        launch(dispatcher) {
            val format = AudioFormat(sampleRate = backend.sampleRate)
            backend.generate(request.text, speakerId, request.speed) { samples ->
                trySend(AudioChunk(samples.toShortPcm(), format)).isSuccess
            }
        }
    }.buffer(Channel.UNLIMITED)
}

/**
 * Recognition with a non-streaming model: the utterance is accumulated and re-decoded every
 * [partialIntervalMs] of new audio to produce partials (needed for barge-in confirmation).
 * Partials stop after [maxPartialAudioMs] because each re-decode costs O(utterance length).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SherpaOfflineAsrEngine(
    private val backend: () -> SherpaOfflineRecognizerBackend?,
    private val partialIntervalMs: Int = 300,
    private val maxPartialAudioMs: Int = 8_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AsrEngine {
    override val id: String = "sherpa-onnx-offline-asr"
    override val kind: EngineKind = EngineKind.Local

    override suspend fun isAvailable(): Boolean = backend() != null

    override fun startSession(config: AsrConfig): AsrSession {
        val backend = backend() ?: throw VoiceEngineException("sherpa-onnx offline recognizer is not configured")
        return Session(backend, config).also { it.start() }
    }

    private inner class Session(
        private val backend: SherpaOfflineRecognizerBackend,
        private val config: AsrConfig,
    ) : BufferedAsrSession(dispatcher) {
        override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
            val rate = config.format.sampleRate
            val interval = config.format.samplesFor(partialIntervalMs)
            val maxPartial = config.format.samplesFor(maxPartialAudioMs)
            var samples = FloatArray(rate * 4)
            var size = 0
            // The first chunk is the pre-roll; start counting partial intervals after it.
            var decodedAt = -1
            var lastPartial = ""
            for (chunk in audio) {
                if (size + chunk.samples.size > samples.size) {
                    samples = samples.copyOf(maxOf(samples.size * 2, size + chunk.samples.size))
                }
                for (s in chunk.samples) samples[size++] = s / 32768f
                if (decodedAt < 0) decodedAt = size
                if (size - decodedAt >= interval && size <= maxPartial && audio.isEmpty) {
                    decodedAt = size
                    val text = backend.decode(samples.copyOf(size), rate).trim()
                    if (text.isNotEmpty() && text != lastPartial) {
                        emit(AsrResult(text, isFinal = false, engineId = id))
                        lastPartial = text
                    }
                }
            }
            if (size == 0) return
            val text = backend.decode(samples.copyOf(size), rate).trim()
            if (text.isNotEmpty()) emit(AsrResult(text, isFinal = true, engineId = id))
        }
    }
}

/**
 * Adapts the frame-size-agnostic [VoiceActivityDetector] contract to Silero's fixed windows.
 * Uses [fallback] until a Silero backend is available.
 */
class SherpaVad(
    private val backend: () -> SherpaVadBackend?,
    private val fallback: VoiceActivityDetector = EnergyVad(),
) : VoiceActivityDetector {
    private var active: SherpaVadBackend? = null
    private var window = FloatArray(0)
    private var filled = 0
    private var lastProbability = 0f

    override fun process(chunk: AudioChunk): Float {
        val current = backend() ?: return fallback.process(chunk)
        if (current !== active) {
            active = current
            window = FloatArray(current.windowSize)
            filled = 0
            current.reset()
        }
        for (sample in chunk.samples) {
            window[filled++] = sample / 32768f
            if (filled == window.size) {
                lastProbability = current.probability(window.copyOf())
                filled = 0
            }
        }
        return lastProbability
    }

    override fun reset() {
        filled = 0
        lastProbability = 0f
        active?.reset()
        fallback.reset()
    }
}
