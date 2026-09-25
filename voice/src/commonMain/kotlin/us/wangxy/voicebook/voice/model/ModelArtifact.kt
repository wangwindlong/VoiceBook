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

/** The local stack: Silero VAD + Paraformer-zh (offline ASR) + Matcha-zh with the Vocos vocoder. */
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

    val ParaformerZh = ModelArtifact(
        id = "stt_paraformer_zh_int8",
        name = "Paraformer 中文识别",
        source = ModelSource.TarBz2("asr-models/sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2"),
        downloadBytes = 218 * MB,
        requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
    )

    val MatchaZh = ModelArtifact(
        id = "tts_matcha_zh_baker",
        name = "Matcha 中文播报",
        source = ModelSource.TarBz2("tts-models/matcha-icefall-zh-baker.tar.bz2"),
        downloadBytes = 71 * MB,
        requiredFiles = listOf("model-steps-3.onnx", "tokens.txt", "lexicon.txt"),
    )

    val VocosVocoder = ModelArtifact(
        id = "tts_vocos_vocoder",
        name = "Vocos 声码器",
        source = ModelSource.SingleFile("vocoder-models/vocos-22khz-univ.onnx"),
        downloadBytes = 51 * MB,
        requiredFiles = listOf("vocos-22khz-univ.onnx"),
    )

    val Required: List<ModelArtifact> = listOf(SileroVad, ParaformerZh, MatchaZh, VocosVocoder)
}
