package us.wangxy.voicebook.voice.model

/** Where an artifact comes from, relative to a download base URL. */
sealed interface ModelSource {
    val path: String

    /** One file stored as `requiredFiles.first()`. */
    data class SingleFile(override val path: String) : ModelSource

    /** A `.tar.bz2` whose top-level directory is stripped on extraction. */
    data class TarBz2(override val path: String) : ModelSource
}

/**
 * A model directory `<modelsRoot>/<id>/`. Same layout and `.dl` / `.source` markers as
 * AVAssistance, so models copied from there are recognised as installed.
 */
data class ModelArtifact(
    val id: String,
    val name: String,
    val source: ModelSource,
    /** Approximate download size, for progress and the first-run prompt. */
    val downloadBytes: Long,
    val requiredFiles: List<String>,
) {
    /** Written to `.source`; a different stored tag means the installed copy is outdated. */
    val sourceTag: String
        get() {
            val file = source.path.substringAfterLast('/')
            return when (source) {
                is ModelSource.TarBz2 -> file.substringBeforeLast(".tar")
                is ModelSource.SingleFile -> file.substringBeforeLast('.')
            }
        }
}

/** The local stack: Silero VAD + streaming X-ASR zipformer-transducer (zh-en ASR) + Matcha-zh-en with the Vocos 16 kHz vocoder. */
object SherpaModels {
    const val OfficialBaseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    /** 【非官方】Third-party GitHub proxies, only tried after the official URL fails. */
    val UnofficialMirrors = listOf(
        "https://ghproxy.net/$OfficialBaseUrl",
        "https://gh-proxy.com/$OfficialBaseUrl",
    )

    private const val MB = 1024L * 1024

    val SileroVad = ModelArtifact(
        id = "vad_silero",
        name = "Silero VAD",
        source = ModelSource.SingleFile("asr-models/silero_vad.onnx"),
        downloadBytes = 1 * MB,
        requiredFiles = listOf("silero_vad.onnx"),
    )

    // X-ASR-zh-en 480ms 流式（默认识别模型）：真流式，边说边出字。encoder/joiner 是 int8、
    // decoder 是 fp32——文件名不统一，requiredFiles 按实际包内容写死。
    val XAsrZhEnStreaming = ModelArtifact(
        id = "stt_x_asr_zh_en_streaming",
        name = "X-ASR 中英混读流式识别",
        source = ModelSource.TarBz2("asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05.tar.bz2"),
        downloadBytes = 128 * MB,
        requiredFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
    )

    // zh-en 的 lexicon.txt 只覆盖中文，英文音素化依赖包内的 espeak-ng-data；
    // phontab 是目录哨兵（目录本身无法用文件校验）。缺了它 matcha 引擎会以
    // "Please provide data dir" 失败。
    val MatchaZhEn = ModelArtifact(
        id = "tts_matcha_zh_en",
        name = "Matcha 中英混读播报",
        source = ModelSource.TarBz2("tts-models/matcha-icefall-zh-en.tar.bz2"),
        downloadBytes = 76 * MB,
        requiredFiles = listOf("model-steps-3.onnx", "tokens.txt", "lexicon.txt", "espeak-ng-data/phontab"),
    )

    // 与旧 zh-baker 的 vocos-22khz-univ.onnx 采样率不同，不可互换。
    val Vocos16kVocoder = ModelArtifact(
        id = "tts_vocos_16k_vocoder",
        name = "Vocos 16kHz 声码器",
        source = ModelSource.SingleFile("vocoder-models/vocos-16khz-univ.onnx"),
        downloadBytes = 51 * MB,
        requiredFiles = listOf("vocos-16khz-univ.onnx"),
    )

    // ---- 回退模型：不在默认下载清单里，仅在设备上已存在时作为回退加载 ----

    /** 上一代默认：离线 X-ASR（被流式版替代，整句精度略高但无中间结果）。 */
    val XAsrZhEn = ModelArtifact(
        id = "stt_x_asr_zh_en",
        name = "X-ASR 中英混读识别（离线，回退）",
        source = ModelSource.TarBz2("asr-models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03.tar.bz2"),
        downloadBytes = 131 * MB,
        requiredFiles = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
    )

    val ParaformerZh = ModelArtifact(
        id = "stt_paraformer_zh_int8",
        name = "Paraformer 中文识别（旧）",
        source = ModelSource.TarBz2("asr-models/sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2"),
        downloadBytes = 218 * MB,
        requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
    )

    val MatchaZhBaker = ModelArtifact(
        id = "tts_matcha_zh_baker",
        name = "Matcha 中文播报（旧）",
        source = ModelSource.TarBz2("tts-models/matcha-icefall-zh-baker.tar.bz2"),
        downloadBytes = 71 * MB,
        requiredFiles = listOf("model-steps-3.onnx", "tokens.txt", "lexicon.txt"),
    )

    val VocosVocoder = ModelArtifact(
        id = "tts_vocos_vocoder",
        name = "Vocos 22kHz 声码器（旧）",
        source = ModelSource.SingleFile("vocoder-models/vocos-22khz-univ.onnx"),
        downloadBytes = 51 * MB,
        requiredFiles = listOf("vocos-22khz-univ.onnx"),
    )

    /** 默认下载清单：流式 X-ASR + Matcha zh-en；旧模型仅在设备上已存在时作为回退加载。 */
    val Required: List<ModelArtifact> = listOf(SileroVad, XAsrZhEnStreaming, MatchaZhEn, Vocos16kVocoder)
}
