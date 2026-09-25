package us.wangxy.voicebook.voice.di

import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.UnsupportedAudioCapture
import us.wangxy.voicebook.voice.audio.UnsupportedAudioPlayer
import us.wangxy.voicebook.voice.local.sherpa.LocalModelSupport
import us.wangxy.voicebook.voice.routing.AssumeOnline
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module

// TODO: javax.sound.sampled TargetDataLine / SourceDataLine. Desktop has no system AEC, so bind a
//  software EchoCanceller (e.g. WebRTC APM via JNI) here as well.
actual fun platformVoiceModule(): Module = module {
    single<AudioCapture> { UnsupportedAudioCapture("desktop JVM") }
    single<AudioPlayer> { UnsupportedAudioPlayer("desktop JVM") }
    single<NetworkMonitor> { AssumeOnline }
    single { LocalModelSupport(modelsDir = null) }
}
