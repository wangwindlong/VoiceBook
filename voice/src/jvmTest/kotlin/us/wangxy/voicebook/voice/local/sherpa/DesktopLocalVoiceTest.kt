package us.wangxy.voicebook.voice.local.sherpa

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import us.wangxy.voicebook.voice.model.ModelRepository
import us.wangxy.voicebook.voice.model.SherpaModels
import io.ktor.client.HttpClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Desktop (Linux x64) acceptance for the local voice stack, mirroring what a phone does on
 * first run: download the models into the real desktop root, build the sherpa backends through
 * [JvmSherpa], then close the loop TTS → VAD → STT. Heavy on first run (~259 MB download);
 * later runs reuse `~/.voicebook/models` and finish in seconds.
 */
class DesktopLocalVoiceTest {

    private lateinit var backends: SherpaBackends

    @BeforeTest
    fun ensureModelsAndBackends() = runBlocking {
        val dir = JvmSherpa.modelsDir() ?: error("sherpa-onnx native runtime not loadable — desktop stack unavailable")
        val baseUrls = listOf(SherpaModels.OfficialBaseUrl) + SherpaModels.UnofficialMirrors
        val repo = ModelRepository(Path(dir.absolutePath), HttpClient(), baseUrls)
        SherpaModels.Required.forEach { artifact ->
            if (!repo.isReady(artifact)) {
                println("[verify] downloading ${artifact.id} (~${artifact.downloadBytes / (1024 * 1024)} MB) …")
                repo.ensure(artifact) { }
                println("[verify] installed ${artifact.id}")
            }
        }
        backends = JvmSherpa.create()
    }

    @Test
    fun ttsVocos16kOutputs16k() {
        val tts = backends.tts ?: error("TTS backend missing")
        assertEquals(16_000, tts.sampleRate, "matcha zh-en + vocos-16khz must output 16 kHz")
    }

    @Test
    fun vadSeparatesSilenceFromSpeech() {
        val vad = backends.vad ?: error("VAD backend missing")
        val tts = backends.tts ?: error("TTS backend missing")
        // Silero is trained on speech; a sine tone scores ~0. Use the TTS output as stimulus.
        val chunks = ArrayList<FloatArray>()
        tts.generate("今天天气真不错，我们一起去公园散步吧。", speakerId = 0, speed = 1.0f) { samples ->
            chunks += samples
            true
        }
        val speech = chunks.reduce { acc, next -> acc + next }
        val speechPcm = ShortArray(speech.size) { (speech[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val speechProb = windowProbabilities(vad, speechPcm).max()
        val silenceProb = windowProbabilities(vad, ShortArray(16_000)).max()
        println("[verify] VAD max prob: speech=%.3f silence=%.3f".format(speechProb, silenceProb))
        assertTrue(speechProb > 0.5f, "speech probability should exceed 0.5, got $speechProb")
        assertTrue(speechProb > silenceProb + 0.3f, "speech should dominate silence ($speechProb vs $silenceProb)")
    }

    @Test
    fun ttsToSttRoundTrip() {
        val tts = backends.tts ?: error("TTS backend missing")
        val asr = backends.offlineRecognizer ?: error("STT backend missing")
        val text = "你好，VoiceBook。Hello desktop voice check."
        val chunks = ArrayList<FloatArray>()
        tts.generate(text, speakerId = 0, speed = 1.0f) { samples ->
            chunks += samples
            true
        }
        val audio = chunks.reduce { acc, next -> acc + next }
        println("[verify] TTS produced %.1f s of audio".format(audio.size / 16_000.0f))
        assertTrue(audio.size > 16_000, "synthesis too short: ${audio.size} samples")

        val recognized = asr.decode(audio, 16_000).trim()
        println("[verify] STT recognized: \"$recognized\"")
        assertTrue(recognized.isNotBlank(), "STT returned empty text for TTS audio")
        assertNotEquals(text, recognized, "placeholder guard: expected real recognition output")
        assertTrue(recognized.contains("VoiceBook") || recognized.contains("voice book", ignoreCase = true) || recognized.contains("你好"),
            "recognized text does not resemble the prompt: \"$recognized\"")
    }

    private fun windowProbabilities(vad: SherpaVadBackend, pcm: ShortArray): List<Float> {
        vad.reset()
        val probs = ArrayList<Float>()
        // Silero only accepts fixed 512-sample windows; drop the trailing partial window.
        pcm.asList().chunked(512).filter { it.size == 512 }.forEach { window ->
            probs += vad.probability(window.map { it / 32768f }.toFloatArray())
        }
        return probs
    }
}
