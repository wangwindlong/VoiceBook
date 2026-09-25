package us.wangxy.voicebook.voice.local.sherpa

import java.io.File
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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/*
 * Model resolution + backend construction shared by the two JVM-flavoured platforms (Android and
 * desktop). Each platform only contributes the sherpa-onnx native library and the model roots;
 * which models are preferred, how they pair up and how backends are built lives here.
 */

/** Resolved model directories; a null entry means that model is not installed under any root. */
class InstalledSherpaModels(
    val vad: File?,
    /** X-ASR zipformer transducer (default). */
    val xAsr: File?,
    /** Legacy Paraformer-zh int8 (fallback). */
    val paraformer: File?,
    val matchaZhEn: File?,
    val vocos16k: File?,
    val matchaBaker: File?,
    val vocos22k: File?,
)

/** First root containing every required file for [id]; null when no root has a complete install. */
private fun modelDir(roots: List<File>, id: String, vararg required: String): File? =
    roots.map { File(it, id) }.firstOrNull { dir -> required.all { File(dir, it).exists() } }

fun findInstalledSherpaModels(roots: List<File>): InstalledSherpaModels {
    val rootList = roots.toList()
    return InstalledSherpaModels(
        vad = modelDir(rootList, "vad_silero", "silero_vad.onnx"),
        xAsr = modelDir(
            rootList,
            "stt_x_asr_zh_en",
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
        paraformer = modelDir(rootList, "stt_paraformer_zh_int8", "model.int8.onnx", "tokens.txt"),
        matchaZhEn = modelDir(rootList, "tts_matcha_zh_en", "model-steps-3.onnx", "tokens.txt", "espeak-ng-data/phontab"),
        vocos16k = modelDir(rootList, "tts_vocos_16k_vocoder", "vocos-16khz-univ.onnx"),
        matchaBaker = modelDir(rootList, "tts_matcha_zh_baker", "model-steps-3.onnx", "tokens.txt"),
        vocos22k = modelDir(rootList, "tts_vocos_vocoder", "vocos-22khz-univ.onnx"),
    )
}

/** [backends] plus the eager-load thunks for the warm-up thread. */
class SherpaStack(val backends: SherpaBackends, val warmup: List<() -> Unit>)

/**
 * New models win; the legacy pair only takes over when the new one is incomplete. The vocoder is
 * paired with its matcha model strictly (16kHz zh-en vs 22kHz zh-baker are not interchangeable).
 */
fun buildSherpaStack(models: InstalledSherpaModels): SherpaStack {
    val vad = models.vad?.let(::SileroVadBackend)
    val asr = models.xAsr?.let(::TransducerOfflineBackend)
        ?: models.paraformer?.let(::ParaformerOfflineBackend)
    val tts = when {
        models.matchaZhEn != null && models.vocos16k != null -> MatchaTtsBackend(models.matchaZhEn, models.vocos16k)
        models.matchaBaker != null && models.vocos22k != null -> MatchaTtsBackend(models.matchaBaker, models.vocos22k)
        else -> null
    }
    val stack = SherpaBackends(offlineRecognizer = asr, tts = tts, vad = vad)
    val warmup = listOfNotNull<() -> Unit>(vad?.let { it::load }, asr?.let { it::load }, tts?.let { it::load })
    return SherpaStack(stack, warmup)
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

/** Legacy fallback: Paraformer-zh int8 (previous default STT), loaded when no X-ASR model is installed. */
internal class ParaformerOfflineBackend(private val dir: File) : SherpaOfflineRecognizerBackend {
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

    override fun load() {
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

/** X-ASR zipformer transducer: encoder/joiner are int8, decoder is fp32 — filenames are fixed. */
internal class TransducerOfflineBackend(private val dir: File) : SherpaOfflineRecognizerBackend {
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
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                    )
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

    override fun load() {
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

internal class MatchaTtsBackend(private val dir: File, private val vocoderDir: File) : SherpaTtsBackend {
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
                        // zh-en 的 lexicon.txt 只覆盖中文，英文音素化靠 espeak-ng 前端数据；
                        // 缺 dataDir 会直接加载失败（"Please provide data dir for this model"）。
                        dataDir = File(dir, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath ?: ""
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
