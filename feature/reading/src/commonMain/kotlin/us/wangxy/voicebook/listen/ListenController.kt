package us.wangxy.voicebook.listen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.listen.ListenScript
import us.wangxy.voicebook.reader.listen.ListenSentence
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.engine.NoEngineAvailableException
import us.wangxy.voicebook.voice.listen.MediaNowPlaying
import us.wangxy.voicebook.voice.listen.MediaPlaybackHost
import us.wangxy.voicebook.voice.listen.MediaTransport
import us.wangxy.voicebook.voice.listen.NoMediaPlaybackHost
import us.wangxy.voicebook.voice.listen.VoiceCommandRecognizer
import us.wangxy.voicebook.voice.model.ModelManager
import us.wangxy.voicebook.voice.model.ModelSetupState
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsRequest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class ListenStatus { Idle, Preparing, Playing, Paused, Failed }

data class ListenState(
    val bookId: Int? = null,
    val title: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    /** The sentence being heard (or the resume point while paused). */
    val sentence: ListenSentence? = null,
    val status: ListenStatus = ListenStatus.Idle,
    val speed: Float = 1f,
    val awaitingCommand: Boolean = false,
    val message: String? = null,
) {
    val active: Boolean get() = bookId != null
    val playing: Boolean get() = status == ListenStatus.Preparing || status == ListenStatus.Playing
}

/**
 * App-lifetime audiobook session: reads a [ReadableBook] aloud sentence by sentence, so it keeps
 * playing after the reader screen is gone. Position is (chapter, char offset) — the reader's own
 * anchor — and is written to reading history as each sentence starts.
 *
 * The next sentences are synthesized while the current one plays and all of them go through a
 * single [AudioPlayer.play] call, so the output stream stays open between sentences. Pausing
 * cancels playback; resuming re-synthesizes from the start of the current sentence.
 *
 * All mutable state is confined to a single-threaded dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenController(
    /** Lazy: resolving the voice engines loads the native runtime, which only listening needs. */
    private val synthesizer: Lazy<SpeechSynthesizer>,
    private val player: AudioPlayer,
    private val models: Lazy<ModelManager>,
    /** Writes the reading-history position; the reader resumes from the same entry. */
    private val saveProgress: suspend (HistoryEntry) -> Unit,
    private val host: MediaPlaybackHost = NoMediaPlaybackHost,
    private val commands: Lazy<VoiceCommandRecognizer>? = null,
    /** Stops other media playback in the app before listening starts. */
    private val beforePlay: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaTransport {
    private val serial = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    val supportsVoiceCommands: Boolean get() = commands != null

    private var book: ReadableBook? = null
    private var meta: HistoryEntry? = null
    private var chapter = 0
    private var offset = 0
    private var speed = 1f
    private var hostActive = false
    private var playJob: Job? = null
    private var commandJob: Job? = null

    /** Bumped whenever playback is cancelled, so late callbacks of an old run are ignored. */
    private var generation = 0L
    private val sentenceCache = HashMap<Int, List<ListenSentence>>()

    /** Starts reading [book] aloud from the sentence containing ([chapterIndex], [charOffset]). */
    fun start(book: ReadableBook, meta: HistoryEntry, chapterIndex: Int, charOffset: Int) {
        scope.launch {
            cancelCommand()
            cancelPlayback()
            this@ListenController.book = book
            this@ListenController.meta = meta
            sentenceCache.clear()
            chapter = chapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
            offset = charOffset.coerceAtLeast(0)
            _state.value = ListenState(
                bookId = meta.bookId,
                title = meta.title,
                author = meta.author,
                coverUrl = meta.coverUrl,
                speed = speed,
            )
            publishCursor(ListenStatus.Paused)
            startPlayback()
        }
    }

    override fun play() {
        scope.launch { if (book != null && !_state.value.playing) startPlayback() }
    }

    override fun pause() {
        scope.launch { pauseInternal() }
    }

    fun toggle() {
        scope.launch { if (_state.value.playing) pauseInternal() else if (book != null) startPlayback() }
    }

    override fun stop() {
        scope.launch { stopInternal() }
    }

    override fun next() = nextSentence()

    override fun previous() = previousSentence()

    fun nextSentence() {
        scope.launch { moveSentence(+1, resume = _state.value.playing) }
    }

    fun previousSentence() {
        scope.launch { moveSentence(-1, resume = _state.value.playing) }
    }

    fun nextChapter() {
        scope.launch { moveChapter(+1, resume = _state.value.playing) }
    }

    fun previousChapter() {
        scope.launch { moveChapter(-1, resume = _state.value.playing) }
    }

    /** Continues from ([chapterIndex], [charOffset]), e.g. "listen from this page". */
    fun seek(chapterIndex: Int, charOffset: Int) {
        scope.launch {
            val b = book ?: return@launch
            relocate(chapterIndex.coerceIn(0, b.chapters.lastIndex), charOffset.coerceAtLeast(0), _state.value.playing)
        }
    }

    fun cycleSpeed() {
        scope.launch {
            val next = Speeds.firstOrNull { it > speed + 0.01f } ?: Speeds.first()
            setSpeedInternal(next, _state.value.playing)
        }
    }

    /**
     * Pauses, listens for one short spoken command, applies it and resumes if playback was
     * running. The microphone is open only for this one command.
     */
    fun listenForCommand() {
        scope.launch {
            val recognizer = commands?.value ?: return@launch
            if (book == null || commandJob?.isActive == true) return@launch
            val wasPlaying = _state.value.playing
            cancelPlayback()
            _state.update {
                it.copy(
                    status = if (it.status == ListenStatus.Failed) it.status else ListenStatus.Paused,
                    awaitingCommand = true,
                    message = "请说：暂停、继续、下一句、上一章、快一点…",
                )
            }
            publishHost()
            commandJob = launch {
                val text = try {
                    recognizer.listenOnce(ListenCommand.Hotwords)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    commandJob = null
                    _state.update { it.copy(awaitingCommand = false, message = "语音指令不可用：${e.message ?: e}") }
                    if (wasPlaying) startPlayback()
                    return@launch
                }
                commandJob = null
                val command = ListenCommand.parse(text)
                _state.update {
                    it.copy(
                        awaitingCommand = false,
                        message = when {
                            command != null -> null
                            text.isBlank() -> "没有听到指令"
                            else -> "没听懂：$text"
                        },
                    )
                }
                apply(command, wasPlaying)
            }
        }
    }

    // region internals (serial)

    private fun apply(command: ListenCommand?, wasPlaying: Boolean) {
        when (command) {
            ListenCommand.Pause -> Unit
            ListenCommand.Resume -> startPlayback()
            ListenCommand.Stop -> stopInternal()
            ListenCommand.NextSentence -> moveSentence(+1, resume = true)
            ListenCommand.PreviousSentence -> moveSentence(-1, resume = true)
            ListenCommand.NextChapter -> moveChapter(+1, resume = true)
            ListenCommand.PreviousChapter -> moveChapter(-1, resume = true)
            ListenCommand.Faster -> setSpeedInternal(Speeds.firstOrNull { it > speed + 0.01f } ?: speed, wasPlaying)
            ListenCommand.Slower -> setSpeedInternal(Speeds.lastOrNull { it < speed - 0.01f } ?: speed, wasPlaying)
            null -> if (wasPlaying) startPlayback()
        }
    }

    private fun startPlayback() {
        val b = book ?: return
        cancelCommand()
        if (!hostActive) {
            if (!host.activate(this)) {
                _state.update { it.copy(status = ListenStatus.Paused, message = "音频被其他应用占用（如通话中），暂时无法播放") }
                return
            }
            hostActive = true
        }
        beforePlay()
        models.value.ensureModels()
        cancelPlayback()
        val gen = generation
        val fromChapter = chapter
        val fromOffset = offset
        val rate = speed
        val warning = runCatching { host.outputWarning() }.getOrNull()
        _state.update { it.copy(status = ListenStatus.Preparing, message = warning) }
        publishHost()
        playJob = scope.launch {
            try {
                player.play(audio(b, fromChapter, fromOffset, rate, gen))
                if (gen == generation) {
                    _state.update { it.copy(status = ListenStatus.Paused, message = "全书已听完") }
                    publishHost()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) {
                    _state.update { it.copy(status = ListenStatus.Failed, message = describe(e)) }
                    publishHost()
                }
            }
        }
    }

    private fun pauseInternal() {
        if (book == null) return
        cancelCommand()
        cancelPlayback()
        _state.update { it.copy(status = ListenStatus.Paused) }
        publishHost()
    }

    private fun stopInternal() {
        cancelCommand()
        cancelPlayback()
        if (hostActive) host.deactivate()
        hostActive = false
        book = null
        meta = null
        sentenceCache.clear()
        _state.value = ListenState(speed = speed)
    }

    private fun cancelPlayback() {
        generation++
        playJob?.let {
            it.cancel()
            player.stopImmediately()
        }
        playJob = null
    }

    private fun cancelCommand() {
        commandJob?.cancel()
        commandJob = null
        if (_state.value.awaitingCommand) _state.update { it.copy(awaitingCommand = false, message = null) }
    }

    private fun setSpeedInternal(value: Float, resume: Boolean) {
        speed = value
        _state.update { it.copy(speed = value) }
        if (resume) startPlayback()
    }

    private fun moveSentence(delta: Int, resume: Boolean) {
        val b = book ?: return
        val list = sentencesOf(b, chapter)
        var index = ListenScript.indexAt(list, offset).let { if (it < 0) list.size else it } + delta
        var target = chapter
        if (index >= list.size) {
            target = chapterWithText(b, chapter + 1, +1) ?: return
            index = 0
        } else if (index < 0) {
            target = chapterWithText(b, chapter - 1, -1) ?: return
            index = sentencesOf(b, target).lastIndex
        }
        relocate(target, sentencesOf(b, target)[index].start, resume)
    }

    private fun moveChapter(delta: Int, resume: Boolean) {
        val b = book ?: return
        val target = chapterWithText(b, chapter + delta, delta) ?: return
        relocate(target, sentencesOf(b, target).first().start, resume)
    }

    private fun relocate(targetChapter: Int, targetOffset: Int, resume: Boolean) {
        cancelPlayback()
        chapter = targetChapter
        offset = targetOffset
        publishCursor(if (resume) ListenStatus.Preparing else ListenStatus.Paused)
        if (resume) startPlayback() else publishHost()
    }

    /** First chapter from [from] in direction [step] that has something to read. */
    private fun chapterWithText(b: ReadableBook, from: Int, step: Int): Int? {
        var ch = from
        while (ch in b.chapters.indices) {
            if (sentencesOf(b, ch).isNotEmpty()) return ch
            ch += step
        }
        return null
    }

    private fun sentencesOf(b: ReadableBook, ch: Int): List<ListenSentence> =
        sentenceCache.getOrPut(ch) { ListenScript.sentences(b.chapterBlocks(ch)) }

    private fun publishCursor(status: ListenStatus) {
        val b = book ?: return
        val list = sentencesOf(b, chapter)
        _state.update {
            it.copy(
                chapterIndex = chapter,
                chapterTitle = b.chapters.getOrNull(chapter)?.title.orEmpty(),
                sentence = list.getOrNull(ListenScript.indexAt(list, offset)),
                status = status,
            )
        }
    }

    private fun publishHost() {
        if (!hostActive) return
        val s = _state.value
        host.update(MediaNowPlaying(title = s.title, subtitle = s.chapterTitle, playing = s.playing))
    }

    /**
     * Sentences from ([fromChapter], [fromOffset]) to the end of the book. A producer synthesizes
     * up to [PrefetchSentences] ahead while the player renders the current one.
     */
    private fun audio(
        b: ReadableBook,
        fromChapter: Int,
        fromOffset: Int,
        rate: Float,
        gen: Long,
    ): Flow<AudioChunk> = flow {
        coroutineScope {
            val prepared = produce(capacity = PrefetchSentences) {
                var ch = fromChapter
                var from = fromOffset
                while (ch < b.chapters.size) {
                    val list = ListenScript.sentences(b.chapterBlocks(ch))
                    val first = ListenScript.indexAt(list, from)
                    if (first >= 0) {
                        for (k in first until list.size) {
                            val sentence = list[k]
                            val audio = synthesizer.value.synthesize(TtsRequest(sentence.text, speed = rate)).toList()
                            send(Prepared(ch, sentence, audio))
                        }
                    }
                    ch++
                    from = 0
                }
            }
            for (p in prepared) {
                // The player has taken everything before this, so it is about to be heard.
                onSentence(gen, p.chapter, p.sentence)
                for (chunk in p.audio) emit(chunk)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun onSentence(gen: Long, ch: Int, sentence: ListenSentence) = withContext(serial) {
        if (gen != generation) return@withContext
        val b = book ?: return@withContext
        val changedChapter = ch != chapter
        chapter = ch
        offset = sentence.start
        _state.update {
            it.copy(
                chapterIndex = ch,
                chapterTitle = if (changedChapter) b.chapters.getOrNull(ch)?.title.orEmpty() else it.chapterTitle,
                sentence = sentence,
                status = ListenStatus.Playing,
            )
        }
        if (changedChapter) sentenceCache.clear()
        publishHost()
        val entry = meta ?: return@withContext
        val progress = ch * 100 / b.chapters.size.coerceAtLeast(1)
        scope.launch {
            runCatching {
                saveProgress(
                    entry.copy(
                        spineIndex = ch,
                        charOffset = sentence.start,
                        progress = progress.coerceIn(0, 100),
                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is NoEngineAvailableException -> when (val s = models.value.state.value) {
            is ModelSetupState.Downloading -> "语音模型下载中（${s.index}/${s.count}），完成后再点播放"
            is ModelSetupState.Extracting -> "语音模型解压中（${s.index}/${s.count}），完成后再点播放"
            is ModelSetupState.Missing -> "本地语音模型未安装，已开始下载"
            is ModelSetupState.Failed -> "语音模型下载失败：${s.message}"
            ModelSetupState.Unsupported -> "当前平台没有本地语音合成，需要配置云端 TTS"
            ModelSetupState.Ready -> "语音合成引擎不可用"
        }
        else -> "朗读失败：${e.message ?: e}"
    }

    // endregion

    private class Prepared(val chapter: Int, val sentence: ListenSentence, val audio: List<AudioChunk>)

    companion object {
        val Speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        /** Sentences synthesized ahead of the one playing. */
        const val PrefetchSentences = 1
    }
}
