package us.wangxy.voicebook.voice.local.sherpa

/*
 * Platform seams for sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx). The shapes mirror the
 * sherpa-onnx Kotlin API: Android wraps it in `AndroidSherpa`; iOS can wrap the C API through
 * cinterop. All methods are blocking and are called off the main thread.
 */

/** Wraps `OnlineRecognizer` (streaming zipformer / paraformer models). */
interface SherpaOnlineRecognizerBackend {
    val sampleRate: Int

    fun createStream(hotwords: List<String>): SherpaOnlineStream
}

/** Wraps `OnlineStream` plus the recognizer calls that operate on it. */
interface SherpaOnlineStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun inputFinished()
    fun isReady(): Boolean
    fun decode()
    fun result(): String
    fun isEndpoint(): Boolean
    fun reset()
    fun release()
}

/** Wraps `OfflineRecognizer` (non-streaming transducer / Paraformer models). */
interface SherpaOfflineRecognizerBackend {
    /** Decodes a complete utterance. Must be safe to call from several sessions. */
    fun decode(samples: FloatArray, sampleRate: Int): String

    /** Optional eager model init for the warm-up thread; default no-op. */
    fun load() {}
}

/** Wraps `OfflineTts` (VITS / Matcha / Kokoro models). */
interface SherpaTtsBackend {
    val sampleRate: Int

    /**
     * Mirrors `OfflineTts.generateWithCallback`: [onSamples] receives audio as it is produced
     * and returns false to abort generation.
     */
    fun generate(text: String, speakerId: Int, speed: Float, onSamples: (FloatArray) -> Boolean)
}

/**
 * Wraps the Silero `Vad`. Uses the raw per-window probability (`Vad.compute`) rather than
 * `isSpeechDetected()`, whose built-in min-speech/min-silence smoothing would delay the onset.
 */
interface SherpaVadBackend {
    /** Samples per inference window (512 for Silero at 16 kHz). */
    val windowSize: Int

    fun probability(window: FloatArray): Float
    fun reset()
}

/**
 * What a platform contributes to local inference. [modelsDir] is where models are installed;
 * null means this platform has no local engines, so nothing is downloaded.
 */
class LocalModelSupport(
    val modelsDir: String?,
    val createBackends: () -> SherpaBackends = { SherpaBackends() },
)

/** Holds the current backends; [reload] picks up models installed after startup. */
class SherpaRuntime(private val create: () -> SherpaBackends) {
    @kotlin.concurrent.Volatile
    var backends: SherpaBackends = create()
        private set

    fun reload() {
        backends = create()
    }
}

/** Whatever the current platform can provide; null means "not available on this device/build". */
data class SherpaBackends(
    /** Preferred for recognition when present (true streaming, cheap partials). */
    val recognizer: SherpaOnlineRecognizerBackend? = null,
    /** Used when no streaming model is installed; partials come from periodic re-decoding. */
    val offlineRecognizer: SherpaOfflineRecognizerBackend? = null,
    val tts: SherpaTtsBackend? = null,
    val vad: SherpaVadBackend? = null,
)
