package us.wangxy.voicebook.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.epub.Span
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.local.sherpa.SherpaTtsBackend
import us.wangxy.voicebook.voice.local.sherpa.SherpaTtsEngine
import us.wangxy.voicebook.voice.model.ModelManager
import us.wangxy.voicebook.voice.routing.EngineRouter
import us.wangxy.voicebook.voice.routing.RoutingPolicy
import us.wangxy.voicebook.voice.routing.RoutingSpeechSynthesizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same wiring as the app (routing + Sherpa engine), with a fake native backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenRealEngineTest {
    private class Book : ReadableBook {
        override val chapters = listOf(EpubBook.Chapter("一", null))
        override fun chapterBlocks(index: Int): List<Block> =
            listOf(Block.Paragraph(0, 6, listOf(Span("你好世界。再见了。"))))
    }

    private class FakeBackend : SherpaTtsBackend {
        override val sampleRate = 16_000
        override fun generate(text: String, speakerId: Int, speed: Float, onSamples: (FloatArray) -> Boolean) {
            onSamples(FloatArray(8_000))
            onSamples(FloatArray(8_000))
        }
    }

    private class CountingPlayer : AudioPlayer {
        var samples = 0L
        override suspend fun play(chunks: Flow<AudioChunk>) {
            chunks.collect { samples += it.samples.size; delay(it.durationMs.toLong()) }
        }
        override fun stopImmediately() = Unit
        override fun setDucked(ducked: Boolean) = Unit
    }

    @Test
    fun sherpaEngineAudioReachesThePlayer() = kotlinx.coroutines.runBlocking {
        val backend = FakeBackend()
        val synth = RoutingSpeechSynthesizer(EngineRouter(SherpaTtsEngine({ backend }), null) { RoutingPolicy.PreferLocal })
        val player = CountingPlayer()
        val controller = ListenController(
            synthesizer = lazyOf(synth),
            player = player,
            models = lazyOf(ModelManager(repository = null, artifacts = emptyList())),
            saveProgress = {},
        )
        controller.start(Book(), HistoryEntry(bookId = 1, title = "t"), 0, 0)
        kotlinx.coroutines.withTimeoutOrNull(10_000) { while (controller.state.value.message != "全书已听完" && controller.state.value.status != ListenStatus.Failed) delay(50) }
        println("state=${controller.state.value} samples=${player.samples}")
        assertEquals(2 * 16_000L, player.samples, "both sentences must be played")
        assertTrue(controller.state.value.message == "全书已听完")
    }
}
