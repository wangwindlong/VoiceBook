package us.wangxy.voicebook.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.epub.Span
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.SpeechRecognizer
import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.listen.VoiceCommandRecognizer
import us.wangxy.voicebook.voice.model.ModelManager
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeBook(chapterTexts: List<List<String>>) : ReadableBook {
    private val blocks = chapterTexts.map { paragraphs ->
        var offset = 0
        paragraphs.map { t -> Block.Paragraph(offset, offset + t.length, listOf(Span(t))).also { offset += t.length } }
    }
    override val chapters = chapterTexts.indices.map { EpubBook.Chapter("第${it}章", null) }
    override fun chapterBlocks(index: Int): List<Block> = blocks.getOrElse(index) { emptyList() }
}

/** Each sentence becomes one 1-second chunk. */
private class RecordingSynthesizer : SpeechSynthesizer {
    val requests = mutableListOf<String>()
    override fun synthesize(request: TtsRequest): Flow<AudioChunk> = flow {
        requests += request.text
        emit(AudioChunk(ShortArray(16_000), AudioFormat.Speech16k))
    }
}

private class RealtimePlayer : AudioPlayer {
    var playCalls = 0
    override suspend fun play(chunks: Flow<AudioChunk>) {
        playCalls++
        chunks.collect { delay(it.durationMs.toLong()) }
    }
    override fun stopImmediately() = Unit
    override fun setDucked(ducked: Boolean) = Unit
}

private class ScriptedRecognizer(private val text: String) : SpeechRecognizer {
    override fun startSession(config: AsrConfig): AsrSession = object : AsrSession {
        override val results: Flow<AsrResult> = flow { emit(AsrResult(text, isFinal = true, engineId = "fake")) }
        override fun sendAudio(chunk: AudioChunk) = Unit
        override fun finish() = Unit
        override fun cancel() = Unit
    }
}

private object SilentMic : AudioCapture {
    override val format = AudioFormat.Speech16k
    override val hasPlatformEchoCancellation = true
    override fun frames(): Flow<AudioChunk> = flow {
        while (true) {
            delay(20)
            emit(AudioChunk(ShortArray(320), format))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListenControllerTest {
    private val book = FakeBook(
        listOf(
            listOf("一。二。"),
            listOf("三。四。"),
        ),
    )
    private val meta = HistoryEntry(bookId = 7, title = "书")

    private class Harness(scope: TestScope, command: String? = null) {
        val synth = RecordingSynthesizer()
        val player = RealtimePlayer()
        val saved = mutableListOf<HistoryEntry>()
        val controller = ListenController(
            synthesizer = lazyOf(synth),
            player = player,
            models = lazyOf(ModelManager(repository = null, artifacts = emptyList())),
            saveProgress = { saved += it },
            commands = command?.let { lazyOf(VoiceCommandRecognizer(SilentMic, ScriptedRecognizer(it))) },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun readsTheWholeBookThroughOneOutputStreamAndRecordsProgress() = runTest {
        val h = Harness(this)
        h.controller.start(book, meta, chapterIndex = 0, charOffset = 0)
        advanceUntilIdle()

        assertEquals(listOf("一。", "二。", "三。", "四。"), h.synth.requests)
        assertEquals(1, h.player.playCalls, "sentences must not reopen the audio output")
        assertEquals(listOf(0 to 0, 0 to 2, 1 to 0, 1 to 2), h.saved.map { it.spineIndex to it.charOffset })
        val state = h.controller.state.value
        assertEquals(ListenStatus.Paused, state.status)
        assertEquals("全书已听完", state.message)
    }

    @Test
    fun startsAtTheSentenceContainingTheOffset() = runTest {
        val h = Harness(this)
        h.controller.start(book, meta, chapterIndex = 1, charOffset = 3)
        advanceUntilIdle()
        assertEquals(listOf("四。"), h.synth.requests)
    }

    @Test
    fun pauseAndResumeRestartTheCurrentSentence() = runTest {
        val h = Harness(this)
        h.controller.start(book, meta, 0, 0)
        advanceTimeBy(1_500) // halfway through "二。"
        runCurrent()
        assertEquals("二。", h.controller.state.value.sentence?.text)

        h.controller.pause()
        runCurrent()
        assertEquals(ListenStatus.Paused, h.controller.state.value.status)

        h.controller.play()
        advanceUntilIdle()
        assertEquals(2, h.player.playCalls)
        assertEquals(listOf("三。", "四。"), h.synth.requests.takeLast(2))
        assertTrue(h.synth.requests.count { it == "二。" } >= 2, "resume re-synthesizes the interrupted sentence")
    }

    @Test
    fun nextSentenceWhilePausedOnlyMovesTheCursor() = runTest {
        val h = Harness(this)
        h.controller.start(book, meta, 0, 0)
        runCurrent()
        h.controller.pause()
        runCurrent()
        h.controller.nextSentence()
        h.controller.nextSentence()
        runCurrent()
        val state = h.controller.state.value
        assertEquals(ListenStatus.Paused, state.status)
        assertEquals(1, state.chapterIndex)
        assertEquals("三。", state.sentence?.text)
    }

    @Test
    fun spokenCommandJumpsToTheNextChapterAndResumes() = runTest {
        val h = Harness(this, command = "下一章。")
        h.controller.start(book, meta, 0, 0)
        advanceTimeBy(500)
        runCurrent()
        h.controller.listenForCommand()
        advanceUntilIdle()

        assertEquals(listOf("三。", "四。"), h.synth.requests.takeLast(2))
        // "二。" was prefetched but never heard: the rest of chapter 0 is skipped.
        assertEquals(listOf(0 to 0, 1 to 0, 1 to 2), h.saved.map { it.spineIndex to it.charOffset })
        assertEquals(false, h.controller.state.value.awaitingCommand)
    }

    @Test
    fun stopClearsTheSession() = runTest {
        val h = Harness(this)
        h.controller.start(book, meta, 0, 0)
        runCurrent()
        h.controller.stop()
        advanceUntilIdle()
        assertEquals(false, h.controller.state.value.active)
        assertEquals(ListenStatus.Idle, h.controller.state.value.status)
    }
}
