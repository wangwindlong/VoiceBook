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

// TODO: getUserMedia({audio: {echoCancellation: true}}) + AudioWorklet for capture, Web Audio for
//  playback; the browser AEC only cancels audio played through the same page.
actual fun platformVoiceModule(): Module = module {
    single<AudioCapture> { UnsupportedAudioCapture("web") }
    single<AudioPlayer> { UnsupportedAudioPlayer("web") }
    single<NetworkMonitor> { AssumeOnline }
    single { LocalModelSupport(modelsDir = null) }
}
