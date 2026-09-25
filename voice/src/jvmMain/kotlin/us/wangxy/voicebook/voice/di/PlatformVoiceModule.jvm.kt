package us.wangxy.voicebook.voice.di

import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.JvmAudioCapture
import us.wangxy.voicebook.voice.audio.JvmAudioPlayer
import us.wangxy.voicebook.voice.local.sherpa.JvmSherpa
import us.wangxy.voicebook.voice.local.sherpa.LocalModelSupport
import us.wangxy.voicebook.voice.routing.AssumeOnline
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module

// Desktop mixers apply no AEC of their own (Linux's PipeWire module-echo-cancel virtual source
// being the exception — JvmAudioCapture detects it). Plug a software EchoCanceller (NLMS /
// WebRTC APM) here when speaker playback echo becomes a problem; headphones avoid it entirely.
actual fun platformVoiceModule(): Module = module {
    single<AudioCapture> { JvmAudioCapture() }
    single<AudioPlayer> { JvmAudioPlayer() }
    single<NetworkMonitor> { AssumeOnline }
    single {
        LocalModelSupport(
            // Non-null whenever the sherpa-onnx native runtime loads → model download enabled.
            modelsDir = JvmSherpa.modelsDir()?.absolutePath,
            createBackends = { JvmSherpa.create() },
        )
    }
}
