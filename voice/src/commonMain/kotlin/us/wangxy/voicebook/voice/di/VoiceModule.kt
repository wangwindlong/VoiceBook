package us.wangxy.voicebook.voice.di

import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.AudioProcessingSettings
import us.wangxy.voicebook.voice.audio.DebugAudioRecorder
import us.wangxy.voicebook.voice.audio.EchoCanceller
import us.wangxy.voicebook.voice.audio.PassThroughEchoCanceller
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.SpeechRecognizer
import us.wangxy.voicebook.voice.duplex.DuplexConfig
import us.wangxy.voicebook.voice.duplex.DuplexVoiceSession
import us.wangxy.voicebook.voice.local.sherpa.LocalModelSupport
import us.wangxy.voicebook.voice.local.sherpa.SherpaLocalAsrEngine
import us.wangxy.voicebook.voice.local.sherpa.SherpaRuntime
import us.wangxy.voicebook.voice.local.sherpa.SherpaTtsEngine
import us.wangxy.voicebook.voice.local.sherpa.SherpaVad
import us.wangxy.voicebook.voice.model.ModelManager
import us.wangxy.voicebook.voice.model.ModelRepository
import us.wangxy.voicebook.voice.model.SherpaModels
import us.wangxy.voicebook.voice.remote.RemoteAsrConfig
import us.wangxy.voicebook.voice.remote.RemoteAsrEngine
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import us.wangxy.voicebook.voice.remote.RemoteTtsConfig
import us.wangxy.voicebook.voice.remote.RemoteTtsEngine
import us.wangxy.voicebook.voice.routing.EngineRouter
import us.wangxy.voicebook.voice.routing.RoutingPolicy
import us.wangxy.voicebook.voice.routing.RoutingSpeechRecognizer
import us.wangxy.voicebook.voice.routing.RoutingSpeechSynthesizer
import us.wangxy.voicebook.voice.routing.VoiceRoutingSettings
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsEngine
import io.ktor.client.HttpClient
import kotlinx.io.files.Path
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

data class VoiceConfig(
    /** Null disables cloud recognition. */
    val remoteAsr: RemoteAsrConfig? = null,
    /** Null disables cloud synthesis. */
    val remoteTts: RemoteTtsConfig? = null,
    val asrPolicy: RoutingPolicy = RoutingPolicy.PreferLocal,
    val ttsPolicy: RoutingPolicy = RoutingPolicy.PreferLocal,
    val duplex: DuplexConfig = DuplexConfig(),
    /** Tried in order for model downloads; replace with an internal mirror where GitHub is blocked. */
    val modelBaseUrls: List<String> = listOf(SherpaModels.OfficialBaseUrl) + SherpaModels.UnofficialMirrors,
)

/**
 * Provides per platform: [AudioCapture], [AudioPlayer], [NetworkMonitor], [LocalModelSupport],
 * and optionally an [EchoCanceller] and a [DebugAudioRecorder].
 */
expect fun platformVoiceModule(): Module

private val VoiceHttpClient = named("voiceHttpClient")
private val ModelHttpClient = named("modelHttpClient")

fun voiceModule(config: VoiceConfig = VoiceConfig()): Module = module {
    includes(platformVoiceModule())

    single { config }
    single { VoiceRoutingSettings(config.asrPolicy, config.ttsPolicy) }
    single { AudioProcessingSettings() }
    single(VoiceHttpClient) {
        HttpClient {
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = 3_000 }
        }
    }

    single(ModelHttpClient) {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 60_000
            }
        }
    }
    single { SherpaRuntime(get<LocalModelSupport>().createBackends) }
    single {
        val runtime = get<SherpaRuntime>()
        val repository = get<LocalModelSupport>().modelsDir?.let { dir ->
            ModelRepository(Path(dir), get(ModelHttpClient), config.modelBaseUrls)
        }
        ModelManager(repository, SherpaModels.Required, onReady = runtime::reload)
    }

    single<SpeechRecognizer> {
        val settings = get<VoiceRoutingSettings>()
        val runtime = get<SherpaRuntime>()
        val local: AsrEngine? = get<LocalModelSupport>().modelsDir?.let { SherpaLocalAsrEngine({ runtime.backends }) }
        val remote: AsrEngine? = config.remoteAsr?.let { RemoteAsrEngine(get(VoiceHttpClient), it, get()) }
        RoutingSpeechRecognizer(EngineRouter(local, remote) { settings.asrPolicy.value })
    }
    single<SpeechSynthesizer> {
        val settings = get<VoiceRoutingSettings>()
        val runtime = get<SherpaRuntime>()
        val local: TtsEngine? = get<LocalModelSupport>().modelsDir?.let { SherpaTtsEngine({ runtime.backends.tts }) }
        val remote: TtsEngine? = config.remoteTts?.let { RemoteTtsEngine(get(VoiceHttpClient), it, get()) }
        RoutingSpeechSynthesizer(EngineRouter(local, remote) { settings.ttsPolicy.value })
    }

    factory {
        val runtime = get<SherpaRuntime>()
        DuplexVoiceSession(
            capture = get(),
            player = get(),
            recognizer = get(),
            synthesizer = get(),
            vad = SherpaVad({ runtime.backends.vad }),
            echoCanceller = getOrNull<EchoCanceller>() ?: PassThroughEchoCanceller,
            config = config.duplex,
        )
    }
}
