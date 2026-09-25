package us.wangxy.voicebook.voice.local.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "VoiceSherpa"

/**
 * Builds sherpa-onnx backends from models found under `<external files>/models/<id>/` or
 * `<files>/models/<id>/` (same layout as AVAssistance). Missing models or native libs simply
 * leave the corresponding backend null, so routing falls through to the cloud engines.
 */
internal object AndroidSherpa {
    private val nativeLibraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa-onnx JNI not available on this ABI; local engines disabled", e)
            false
        }
    }

    fun isSupported(): Boolean = nativeLibraryLoaded

    fun create(context: Context?): SherpaBackends {
        if (context == null || !nativeLibraryLoaded) return SherpaBackends()
        val roots = listOfNotNull(context.getExternalFilesDir("models"), File(context.filesDir, "models"))
        fun model(id: String, vararg required: String): File? =
            roots.map { File(it, id) }.firstOrNull { dir -> required.all { File(dir, it).exists() } }

        val vad = model("vad_silero", "silero_vad.onnx")?.let(::SileroVadBackend)
        val asr = model("stt_paraformer_zh_int8", "model.int8.onnx", "tokens.txt")?.let(::ParaformerOfflineBackend)
        val matcha = model("tts_matcha_zh_baker", "model-steps-3.onnx", "tokens.txt")
        val vocos = model("tts_vocos_vocoder", "vocos-22khz-univ.onnx")
        val tts = if (matcha != null && vocos != null) MatchaTtsBackend(matcha, vocos) else null
        Log.i(TAG, "models under $roots: vad=${vad != null} asr=${asr != null} tts=${tts != null}")

        thread(name = "sherpa-warmup", isDaemon = true) {
            listOfNotNull<() -> Unit>(vad?.let { it::load }, asr?.let { it::load }, tts?.let { it::load }).forEach { load ->
                runCatching(load).onFailure { Log.e(TAG, "model warm-up failed", it) }
            }
        }
        return SherpaBackends(offlineRecognizer = asr, tts = tts, vad = vad)
    }
}

private class SileroVadBackend(private val dir: File) : SherpaVadBackend {
    override val windowSize: Int = 512
    private val vad by lazy {
        Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig().apply {
                    model = File(dir, "silero_vad.onnx").absolutePath
                    threshold = 0.5f
                    windowSize = this@SileroVadBackend.windowSize
                },
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
    }

    fun load() {
        vad
    }

    override fun probability(window: FloatArray): Float = vad.compute(window)

    override fun reset() = vad.reset()
}

private class ParaformerOfflineBackend(private val dir: File) : SherpaOfflineRecognizerBackend {
    private val recognizer by lazy {
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(16_000, 80, 0f),
                modelConfig = OfflineModelConfig().apply {
                    numThreads = 4
                    provider = "cpu"
                    debug = false
                    tokens = File(dir, "tokens.txt").absolutePath
                    paraformer = OfflineParaformerModelConfig(model = File(dir, "model.int8.onnx").absolutePath)
                },
                hr = HomophoneReplacerConfig(),
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = "",
                hotwordsScore = 1.5f,
                ruleFsts = "",
                ruleFars = "",
                blankPenalty = 0f,
            ),
        )
    }

    fun load() {
        recognizer
    }

    @Synchronized
    override fun decode(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }
}

private class MatchaTtsBackend(private val dir: File, private val vocoderDir: File) : SherpaTtsBackend {
    private val tts by lazy {
        val acoustic = dir.listFiles()!!.first { it.name.startsWith("model-steps") && it.name.endsWith(".onnx") }
        val vocoder = vocoderDir.listFiles()!!.first { it.name.startsWith("vocos") && it.name.endsWith(".onnx") }
        OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig().apply {
                    numThreads = 2
                    debug = false
                    provider = "cpu"
                    matcha = OfflineTtsMatchaModelConfig().apply {
                        acousticModel = acoustic.absolutePath
                        this.vocoder = vocoder.absolutePath
                        tokens = File(dir, "tokens.txt").absolutePath
                        lexicon = File(dir, "lexicon.txt").takeIf { it.exists() }?.absolutePath ?: ""
                        dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath ?: ""
                        noiseScale = 0.45f
                    }
                },
                ruleFsts = listOf("phone.fst", "date.fst", "number.fst")
                    .map { File(dir, it) }.filter { it.exists() }.joinToString(",") { it.absolutePath },
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.6f,
            ),
        )
    }

    fun load() {
        tts
    }

    override val sampleRate: Int get() = tts.sampleRate()

    /** Serialized: an interrupted utterance finishes aborting before the next one starts. */
    @Synchronized
    override fun generate(text: String, speakerId: Int, speed: Float, onSamples: (FloatArray) -> Boolean) {
        // Must be a real class, not a lambda: the JNI side looks up `invoke([F)Ljava/lang/Integer;`,
        // which an indy lambda desugared by D8 doesn't expose (crashes the process on device).
        tts.generateWithCallback(
            text,
            speakerId,
            speed,
            object : Function1<FloatArray, Int> {
                override fun invoke(chunk: FloatArray): Int = if (chunk.isEmpty() || onSamples(chunk)) 1 else 0
            },
        )
    }
}
