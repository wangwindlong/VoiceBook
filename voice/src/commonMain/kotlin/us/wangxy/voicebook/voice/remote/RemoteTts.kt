package us.wangxy.voicebook.voice.remote

import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.Pcm16LeDecoder
import us.wangxy.voicebook.voice.engine.EngineKind
import us.wangxy.voicebook.voice.engine.VoiceEngineException
import us.wangxy.voicebook.voice.routing.NetworkMonitor
import us.wangxy.voicebook.voice.tts.TtsEngine
import us.wangxy.voicebook.voice.tts.TtsRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RemoteTtsConfig(
    /** HTTP endpoint that streams synthesized audio back in the response body. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val sampleRate: Int = 16_000,
)

/** Adapts [RemoteTtsEngine] to a vendor's HTTP streaming TTS API. */
interface RemoteTtsProtocol {
    fun configureRequest(builder: HttpRequestBuilder, remote: RemoteTtsConfig, request: TtsRequest)
    fun outputFormat(remote: RemoteTtsConfig, request: TtsRequest): AudioFormat

    /** Decodes the streamed response body into PCM as bytes arrive. */
    suspend fun decode(body: ByteReadChannel, format: AudioFormat, emit: suspend (AudioChunk) -> Unit)
}

/**
 * Reference protocol: POST `{"text","voice","speed","language","sample_rate","format":"pcm_s16le"}`,
 * response body is raw mono PCM16LE streamed with chunked transfer encoding.
 */
class GenericHttpTtsProtocol(private val readChunkMs: Int = 40) : RemoteTtsProtocol {
    override fun configureRequest(builder: HttpRequestBuilder, remote: RemoteTtsConfig, request: TtsRequest) {
        builder.method = HttpMethod.Post
        builder.url(remote.url)
        remote.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.contentType(ContentType.Application.Json)
        builder.setBody(
            buildJsonObject {
                put("text", request.text)
                request.voice?.let { put("voice", it) }
                put("speed", request.speed)
                put("language", request.language)
                put("sample_rate", remote.sampleRate)
                put("format", "pcm_s16le")
            }.toString()
        )
    }

    override fun outputFormat(remote: RemoteTtsConfig, request: TtsRequest) = AudioFormat(remote.sampleRate)

    override suspend fun decode(body: ByteReadChannel, format: AudioFormat, emit: suspend (AudioChunk) -> Unit) {
        val decoder = Pcm16LeDecoder()
        val buffer = ByteArray(format.samplesFor(readChunkMs) * 2)
        while (true) {
            val n = body.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n == 0) continue
            val samples = decoder.decode(buffer, n)
            if (samples.isNotEmpty()) emit(AudioChunk(samples, format))
        }
    }
}

class RemoteTtsEngine(
    private val client: HttpClient,
    private val config: RemoteTtsConfig,
    private val network: NetworkMonitor,
    private val protocol: RemoteTtsProtocol = GenericHttpTtsProtocol(),
) : TtsEngine {
    override val id: String = "remote-tts"
    override val kind: EngineKind = EngineKind.Remote

    override suspend fun isAvailable(): Boolean = config.url.isNotBlank() && network.isOnline

    override fun synthesize(request: TtsRequest): Flow<AudioChunk> = channelFlow {
        val format = protocol.outputFormat(config, request)
        client.prepareRequest { protocol.configureRequest(this, config, request) }.execute { response ->
            if (!response.status.isSuccess()) throw VoiceEngineException("Remote TTS failed: HTTP ${response.status}")
            protocol.decode(response.bodyAsChannel(), format) { send(it) }
        }
    }
}
