package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrEngine
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.BufferedAsrSession
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.engine.EngineKind
import us.wangxy.voicebook.voice.engine.NoEngineAvailableException
import us.wangxy.voicebook.voice.engine.VoiceEngineException
import us.wangxy.voicebook.voice.routing.EngineRouter
import us.wangxy.voicebook.voice.routing.RoutingPolicy
import us.wangxy.voicebook.voice.routing.RoutingSpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutingTest {
    /** Cloud engine whose connection drops after a few frames. */
    private class DroppingEngine(private val failAfter: Int) : AsrEngine {
        override val id = "remote"
        override val kind = EngineKind.Remote
        override suspend fun isAvailable() = true
        override fun startSession(config: AsrConfig): AsrSession = object : BufferedAsrSession() {
            override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
                var n = 0
                for (chunk in audio) if (++n >= failAfter) throw VoiceEngineException("connection lost")
            }
        }.start()
    }

    /** Local engine that reports how many frames it received. */
    private class CountingEngine(private val available: Boolean = true) : AsrEngine {
        override val id = "local"
        override val kind = EngineKind.Local
        override suspend fun isAvailable() = available
        override fun startSession(config: AsrConfig): AsrSession = object : BufferedAsrSession() {
            override suspend fun run(audio: ReceiveChannel<AudioChunk>, emit: suspend (AsrResult) -> Unit) {
                var n = 0
                for (chunk in audio) n++
                emit(AsrResult("frames=$n", isFinal = true, engineId = id))
            }
        }.start()
    }

    @Test
    fun failoverReplaysEverySentFrame() = runTest {
        withContext(Dispatchers.Default) {
            val router = EngineRouter<AsrEngine>(CountingEngine(), DroppingEngine(failAfter = 3)) { RoutingPolicy.PreferRemote }
            val session = RoutingSpeechRecognizer(router).startSession(AsrConfig())
            repeat(10) { session.sendAudio(frame(Marker.USER)) }
            session.finish()
            val results = withTimeout(5_000) { session.results.toList() }
            assertEquals(listOf("frames=10"), results.map { it.text })
            assertEquals("local", results.single().engineId)
        }
    }

    @Test
    fun remoteOnlyWithoutRemoteFails() = runTest {
        withContext(Dispatchers.Default) {
            val router = EngineRouter<AsrEngine>(CountingEngine(), null) { RoutingPolicy.RemoteOnly }
            val session = RoutingSpeechRecognizer(router).startSession(AsrConfig())
            session.finish()
            assertFailsWith<NoEngineAvailableException> { withTimeout(5_000) { session.results.toList() } }
        }
    }

    @Test
    fun unavailableEnginesAreSkipped() = runTest {
        val router = EngineRouter<AsrEngine>(CountingEngine(available = false), DroppingEngine(1)) { RoutingPolicy.PreferLocal }
        assertEquals(listOf("remote"), router.candidates().map { it.id })
    }
}
