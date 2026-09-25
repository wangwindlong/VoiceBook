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

// Local sherpa engines need the sherpa-onnx iOS framework (C API via cinterop); until then models
// aren't downloaded on iOS and recognition/synthesis route to the cloud engines.
actual fun platformVoiceModule(): Module = module {
    single { IosAudioEngine(get()) }
    single<AudioCapture> { IosAudioCapture(get()) }
    single<AudioPlayer> { IosAudioPlayer(get()) }
    single<NetworkMonitor> { AssumeOnline }
    single { LocalModelSupport(modelsDir = null) }
}
