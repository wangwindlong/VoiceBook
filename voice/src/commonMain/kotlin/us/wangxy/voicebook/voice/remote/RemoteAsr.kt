package us.wangxy.voicebook.voice.remote

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.BufferedAsrSession
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.toLittleEndianBytes
import us.wangxy.voicebook.voice.engine.EngineKind
import us.wangxy.voicebook.voice.engine.VoiceEngineException
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class RemoteAsrConfig(
    /** WebSocket endpoint, e.g. wss://asr.example.com/v1/stream */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** How long to wait for the final result after the end of audio was sent. */
    val finalResultTimeoutMs: Long = 3_000,
)

sealed interface RemoteAsrMessage {
    data class Hypothesis(val text: String, val isFinal: Boolean) : RemoteAsrMessage
    data object Completed : RemoteAsrMessage
}

/** Adapts [RemoteAsrEngine] to a vendor's streaming WebSocket protocol. */
interface RemoteAsrProtocol {
    fun configureRequest(builder: HttpRequestBuilder, remote: RemoteAsrConfig, asr: AsrConfig)
    fun startFrame(asr: AsrConfig): Frame?
    fun audioFrame(chunk: AudioChunk): Frame
    fun endFrame(): Frame?

    /** Returns null for frames that carry nothing relevant; throws on server-reported errors. */
    fun parse(frame: Frame): RemoteAsrMessage?
}

/**
 * Reference protocol for a self-hosted gateway:
 * client -> `{"type":"start",...}`, binary PCM16LE frames, `{"type":"end"}`;
 * server -> `{"type":"partial|final","text":"..."}`, `{"type":"completed"}`, `{"type":"error","message":"..."}`.
 */
class GenericJsonAsrProtocol : RemoteAsrProtocol {
    override fun configureRequest(builder: HttpRequestBuilder, remote: RemoteAsrConfig, asr: AsrConfig) {
        builder.url(remote.url)
        remote.headers.forEach { (k, v) -> builder.header(k, v) }
    }

    override fun startFrame(asr: AsrConfig): Frame = Frame.Text(
        buildJsonObject {
            put("type", "start")
            put("sample_rate", asr.format.sampleRate)
            put("channels", asr.format.channels)
            put("encoding", "pcm_s16le")
            put("language", asr.language)
            put("partial_results", true)
            putJsonArray("hotwords") { asr.hotwords.forEach { add(it) } }
        }.toString()
    )

    override fun audioFrame(chunk: AudioChunk): Frame = Frame.Binary(true, chunk.samples.toLittleEndianBytes())

    override fun endFrame(): Frame = Frame.Text("""{"type":"end"}""")

    override fun parse(frame: Frame): RemoteAsrMessage? {
        if (frame !is Frame.Text) return null
        val obj = Json.parseToJsonElement(frame.readText()).jsonObject
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
        return when (obj["type"]?.jsonPrimitive?.content) {
            "partial" -> RemoteAsrMessage.Hypothesis(text, isFinal = false)
            "final" -> RemoteAsrMessage.Hypothesis(text, isFinal = true)
            "completed" -> RemoteAsrMessage.Completed
            "error" -> throw VoiceEngineException("Remote ASR error: ${obj["message"]?.jsonPrimitive?.content}")
            else -> null
        }
    }
}

class RemoteAsrEngine(
    private val client: HttpClient,
    private val config: RemoteAsrConfig,
    private val network: NetworkMonitor,
    private val protocol: RemoteAsrProtocol = GenericJsonAsrProtocol(),
) : AsrEngine {
    override val id: String = "remote-asr"
    override val kind: EngineKind = EngineKind.Remote

    override suspend fun isAvailable(): Boolean = config.url.isNotBlank() && network.isOnline

    override fun startSession(config: AsrConfig): AsrSession = Session(config).also { it.start() }

    private inner class Session(private val asr: AsrConfig) : BufferedAsrSession() {
        override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
            client.webSocket(request = { protocol.configureRequest(this, config, asr) }) {
                coroutineScope {
                    protocol.startFrame(asr)?.let { send(it) }
                    val receiver = launch {
                        for (frame in incoming) {
                            when (val message = protocol.parse(frame)) {
                                is RemoteAsrMessage.Hypothesis ->
                                    if (message.text.isNotBlank()) emit(AsrResult(message.text, message.isFinal, id))
                                RemoteAsrMessage.Completed -> break
                                null -> Unit
                            }
                        }
                    }
                    for (chunk in audio) send(protocol.audioFrame(chunk))
                    protocol.endFrame()?.let { send(it) }
                    if (withTimeoutOrNull(config.finalResultTimeoutMs) { receiver.join() } == null) {
                        receiver.cancel()
                    }
                }
            }
        }
    }
}
