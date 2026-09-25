package us.wangxy.voicebook.voice.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import us.wangxy.voicebook.voice.audio.AndroidAudioCapture
import us.wangxy.voicebook.voice.audio.AndroidAudioPlayer
import us.wangxy.voicebook.voice.audio.AndroidAudioRouting
import us.wangxy.voicebook.voice.audio.AndroidDebugAudioRecorder
import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.DebugAudioRecorder
import us.wangxy.voicebook.voice.local.sherpa.AndroidSherpa
import us.wangxy.voicebook.voice.local.sherpa.LocalModelSupport
import us.wangxy.voicebook.voice.routing.AssumeOnline
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

/** Expects the application to register its [Context] in Koin. */
actual fun platformVoiceModule(): Module = module {
    single { AndroidAudioRouting(getOrNull<Context>(), get()) }
    single<AudioCapture> { AndroidAudioCapture(getOrNull<Context>(), get()) }
    single<AudioPlayer> { AndroidAudioPlayer(get()) }
    single<NetworkMonitor> { getOrNull<Context>()?.let(::AndroidNetworkMonitor) ?: AssumeOnline }
    single {
        val context = getOrNull<Context>()
        LocalModelSupport(
            modelsDir = context?.takeIf { AndroidSherpa.isSupported() }?.let { File(it.filesDir, "models").absolutePath },
            createBackends = { AndroidSherpa.create(context) },
        )
    }
    single<DebugAudioRecorder> { AndroidDebugAudioRecorder(getOrNull<Context>()) }
}

private class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Boolean
        get() {
            val cm = connectivity ?: return true
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
}
