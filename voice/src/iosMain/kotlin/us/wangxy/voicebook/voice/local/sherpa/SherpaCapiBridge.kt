package us.wangxy.voicebook.voice.local.sherpa

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLog
import us.wangxy.voicebook.voice.capi.SherpaOnnxCreateVoiceActivityDetector
import us.wangxy.voicebook.voice.capi.SherpaOnnxDestroySpeechSegment
import us.wangxy.voicebook.voice.capi.SherpaOnnxDestroyVoiceActivityDetector
import us.wangxy.voicebook.voice.capi.SherpaOnnxGetVersionStr
import us.wangxy.voicebook.voice.capi.SherpaOnnxVoiceActivityDetectorAcceptWaveform
import us.wangxy.voicebook.voice.capi.SherpaOnnxVoiceActivityDetectorDetected
import us.wangxy.voicebook.voice.capi.SherpaOnnxVoiceActivityDetectorFlush
import us.wangxy.voicebook.voice.capi.SherpaOnnxVoiceActivityDetectorFront

/**
 * iOS 侧 sherpa-onnx C API 桥（cinterop）。
 *
 * 为什么走 C API 而不是 JNI：Kotlin/Native 加载不了 JVM 的 JNI 动态库，
 * Android 那套 libsherpa-onnx-jni.so 在 iOS 完全不可用。官方为 iOS 提供的正是
 * C API（sherpa-onnx.xcframework，SHERPA_ONNX_ENABLE_JNI=OFF +
 * SHERPA_ONNX_ENABLE_C_API=ON），由 scripts/build_sherpa_ios.sh 产出。
 *
 * 本文件只覆盖「验证通路」所需的最小闭环：版本探针 → VAD 创建 → 喂音频 →
 * 检测 → 取段 → 销毁。其余引擎（流式/离线 ASR、Matcha TTS）按同一模板追加即可，
 * c-api.h 已全部暴露；接上后替换 PlatformVoiceModule 里的云端兜底。
 *
 * 注意：本文件依赖 cinterop 生成包 `us.wangxy.voicebook.voice.capi`，仅在
 * voice/ios/sherpa-onnx-<ver>-ios/ 就位（即 cinterop 已启用）时才参与编译——
 * voice/build.gradle.kts 会在未就位时排除它，保证 Linux/CI 可编译。
 *
 * 另：sherpa-onnx 的 C 结构体新增字段会改变布局，头文件与静态库必须同版本
 * （两者都由 scripts/build_sherpa_ios.sh 产出，天然一致）。
 */
@OptIn(ExperimentalForeignApi::class)
object SherpaCapiBridge {

    /** 静态库版本。用来确认 cinterop 真把库链上了，而不只是头文件能编译。 */
    fun version(): String = SherpaOnnxGetVersionStr()?.toKString() ?: "unknown"

    /**
     * VAD 最小闭环自检：静音缓冲走一遍 创建 → 喂数据 → flush → 检测 → 取段 → 销毁。
     *
     * 判据与 Android 真机探针一致：静音必须判定为「非语音」。若得到 true，
     * 说明配置或结构体布局有问题。
     */
    fun selfCheck(vadModelPath: String): VadSelfCheck = memScoped {
        var stage = "create"
        try {
            val config = alloc<us.wangxy.voicebook.voice.capi.SherpaOnnxVadModelConfig>()
            config.silero_vad.model = vadModelPath.cstr.ptr
            config.silero_vad.threshold = 0.5f
            config.silero_vad.min_silence_duration = 0.5f
            config.silero_vad.min_speech_duration = 0.25f
            config.silero_vad.window_size = 512
            config.silero_vad.max_speech_duration = 5f
            config.sample_rate = 16000
            config.num_threads = 1
            config.provider = "cpu".cstr.ptr
            config.debug = 0

            stage = "create-detector"
            val vad = SherpaOnnxCreateVoiceActivityDetector(config.ptr, 30f)
                ?: return@memScoped VadSelfCheck(false, stage, "返回空指针")

            stage = "accept"
            val silence = FloatArray(8000) // 0.5s @16k
            silence.usePinned { pinned ->
                SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad, pinned.addressOf(0), silence.size)
            }

            stage = "flush"
            SherpaOnnxVoiceActivityDetectorFlush(vad)

            stage = "detect"
            val detected = SherpaOnnxVoiceActivityDetectorDetected(vad) != 0

            stage = "front"
            SherpaOnnxVoiceActivityDetectorFront(vad)?.let { SherpaOnnxDestroySpeechSegment(it) }

            stage = "destroy"
            SherpaOnnxDestroyVoiceActivityDetector(vad)

            VadSelfCheck(
                ok = !detected,
                stage = "done",
                detail = if (detected) "静音被判为语音，配置或结构体布局可能有误" else "调用链完整，静音判定正确",
            )
        } catch (t: Throwable) {
            NSLog("SherpaCapiBridge.selfCheck 在 $stage 阶段失败: ${t.message}")
            VadSelfCheck(false, stage, t.message ?: t.toString())
        }
    }
}

/** 自检结果。 */
data class VadSelfCheck(
    val ok: Boolean,
    val stage: String,
    val detail: String,
)
