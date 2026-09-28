package us.wangxy.voicebook.voice.di

import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.IosAudioCapture
import us.wangxy.voicebook.voice.audio.IosAudioEngine
import us.wangxy.voicebook.voice.audio.IosAudioPlayer
import us.wangxy.voicebook.voice.local.sherpa.LocalModelSupport
import us.wangxy.voicebook.voice.routing.AssumeOnline
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module

// 本地推理的打包链路已就绪：scripts/build_sherpa_ios.sh 在 macOS 上产出
// SherpaOnnxC.xcframework（C API），voice 模块 cinterop 条件接线，SherpaCapiBridge
// 提供 VAD 自检通路。但 ASR/TTS 引擎的 C API 移植尚未开始，期间 modelsDir 保持
// null：不下载模型，识别/合成全部路由到云端引擎。
actual fun platformVoiceModule(): Module = module {
    single { IosAudioEngine(get()) }
    single<AudioCapture> { IosAudioCapture(get()) }
    single<AudioPlayer> { IosAudioPlayer(get()) }
    single<NetworkMonitor> { AssumeOnline }
    single { LocalModelSupport(modelsDir = null) }
}
