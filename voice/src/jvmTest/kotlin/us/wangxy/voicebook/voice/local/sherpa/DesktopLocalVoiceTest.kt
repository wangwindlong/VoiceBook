package us.wangxy.voicebook.voice.local.sherpa

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.toShortPcm
import us.wangxy.voicebook.voice.model.ModelRepository
import us.wangxy.voicebook.voice.model.SherpaModels
import io.ktor.client.HttpClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val asr = backends.recognizer ?: error("streaming STT backend missing")
        val tts = backends.tts ?: error("TTS backend missing")
        val text = "你好，VoiceBook。Hello desktop voice check."
        val chunks = ArrayList<FloatArray>()
        tts.generate(text, speakerId = 0, speed = 1.0f) { samples ->
            chunks += samples
            true
        }
        val audio = chunks.reduce { acc, next -> acc + next }
        println("[verify] TTS produced %.1f s of audio".format(audio.size / 16_000.0f))
        assertTrue(audio.size > 16_000, "synthesis too short: ${audio.size} samples")

        // Feed in 100 ms chunks like a live mic would; track partials continuously and collect
        // the last one on each endpoint (an endpoint reset can drop the still-decoding tail).
        val stream = asr.createStream(emptyList())
        val collected = ArrayList<String>()
        var lastPartial = ""
        try {
            audio.toList().chunked(1_600).forEach { chunk ->
                stream.acceptWaveform(chunk.toFloatArray(), 16_000)
                while (stream.isReady()) stream.decode()
                stream.result().trim().let { if (it.isNotBlank()) lastPartial = it }
                if (stream.isEndpoint()) {
                    if (lastPartial.isNotBlank()) collected += lastPartial
                    lastPartial = ""
                    stream.reset()
                }
            }
            // A real mic always has trailing silence after the last word; the streaming
            // decoder needs those frames to flush the final tokens (e.g. a short "CHECK").
            repeat(10) {
                stream.acceptWaveform(FloatArray(1_600), 16_000)
                while (stream.isReady()) stream.decode()
                stream.result().trim().let { if (it.isNotBlank()) lastPartial = it }
                if (stream.isEndpoint()) {
                    if (lastPartial.isNotBlank()) collected += lastPartial
                    lastPartial = ""
                    stream.reset()
                }
            }
            stream.inputFinished()
            while (stream.isReady()) stream.decode()
            val final = stream.result().trim()
            collected += if (final.isNotBlank()) final else lastPartial
        } finally {
            stream.release()
        }
        val recognized = collected.filter { it.isNotBlank() }.joinToString(" ")
        println("[verify] STT (streaming) recognized: \"$recognized\"")
        assertTrue(recognized.isNotBlank(), "streaming STT returned empty text for TTS audio")
        val normalized = recognized.replace(" ", "")
        assertTrue(
            normalized.contains("voicebook", ignoreCase = true) || recognized.contains("你好"),
            "recognized text does not resemble the prompt: \"$recognized\"",
        )
    }

    /**
     * Regression for the dropped-tail bug: a session that stops right after the last word must
     * still recognize it (the engine flushes the chunked decoder with a synthetic silence tail).
     * Drives the real session with NO trailing silence.
     */
    @Test
    fun sessionFlushesTailWithoutTrailingSilence() = runBlocking {
        val engine = SherpaAsrEngine({ backends.recognizer ?: error("streaming STT backend missing") })
        val session = engine.startSession(AsrConfig())
        val results = ArrayList<AsrResult>()
        withTimeout(120_000) {
            val collector = launch { session.results.collect { results += it } }
            val tts = backends.tts ?: error("TTS backend missing")
            val chunks = ArrayList<FloatArray>()
            tts.generate("你好，VoiceBook。Hello desktop voice check.", speakerId = 0, speed = 1.0f) { samples ->
                chunks += samples
                true
            }
            val pcm = chunks.reduce { acc, next -> acc + next }.toShortPcm()
            pcm.toList().chunked(1_600).forEach { chunk ->
                session.sendAudio(AudioChunk(chunk.toShortArray(), AudioFormat.Speech16k))
            }
            session.finish()
            collector.join()
        }
        val all = results.joinToString(" ") { it.text }
        println("[verify] session results: ${results.map { if (it.isFinal) "[F]${it.text}" else "[P]${it.text}" }}")
        val normalized = all.replace(" ", "")
        assertTrue(normalized.contains("voicebook", ignoreCase = true), "expected VoiceBook in: \"$all\"")
        assertTrue(
            normalized.contains("check", ignoreCase = true),
            "tail word CHECK missing — the session did not flush its tail: \"$all\"",
        )
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
