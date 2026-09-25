package us.wangxy.voicebook.voice.duplex

import us.wangxy.voicebook.voice.asr.AsrResult
import us.wangxy.voicebook.voice.asr.AsrSession
import us.wangxy.voicebook.voice.asr.SpeechRecognizer
import us.wangxy.voicebook.voice.audio.AudioCapture
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioPlayer
import us.wangxy.voicebook.voice.audio.EchoCanceller
import us.wangxy.voicebook.voice.audio.PassThroughEchoCanceller
import us.wangxy.voicebook.voice.audio.PcmRingBuffer
import us.wangxy.voicebook.voice.audio.levelDb
import us.wangxy.voicebook.voice.tts.SpeechSynthesizer
import us.wangxy.voicebook.voice.tts.TtsRequest
import us.wangxy.voicebook.voice.vad.SpeechDetector
import us.wangxy.voicebook.voice.vad.SpeechEvent
import us.wangxy.voicebook.voice.vad.VoiceActivityDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Full-duplex voice loop: the microphone stays open while TTS plays, so the user can interrupt
 * (barge in) at any time.
 *
 * Self-echo is rejected in three layers: platform/software AEC on the mic signal, a stricter VAD
 * while playing, and [EchoTextFilter] on recognized text. No words are lost because every mic
 * frame goes through a pre-roll ring buffer, and the ASR stream is opened at speech onset (not at
 * confirmation) starting with that buffer.
 *
 * All mutable state is confined to a single-threaded dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplexVoiceSession(
    private val capture: AudioCapture,
    private val player: AudioPlayer,
    private val recognizer: SpeechRecognizer,
    private val synthesizer: SpeechSynthesizer,
    private val vad: VoiceActivityDetector,
    private val echoCanceller: EchoCanceller = PassThroughEchoCanceller,
    private val config: DuplexConfig = DuplexConfig(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val serial = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow(DuplexState())
    val state: StateFlow<DuplexState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val preRoll = PcmRingBuffer(capture.format.samplesFor(config.preRollMs))
    private val detector = SpeechDetector(config.detector)
    private val echoFilter = EchoTextFilter(config.echoText)
    private val backchannelFilter = BackchannelFilter(config.backchannel)
    private val asrConfig = config.asr.copy(format = capture.format)
    private val monitors = mutableListOf<DuplexMonitor>()

    private var micJob: Job? = null
    private var utterance: Utterance? = null
    private var turn: SpeakTurn? = null
    private var lastSpokenText: String? = null
    private var playbackEndedAt: TimeMark? = null
    private val minSpeechLevelDb = config.detector.minSpeechLevelDb
    private var nextUtteranceId = 1L
    private var nextTurnId = 1L

    /** Opens the microphone and starts listening continuously. */
    fun start() {
        scope.launch {
            if (micJob?.isActive == true) return@launch
            micJob = launch { runMic() }
        }
    }

    /** Stops listening and speaking. The session can be started again. */
    fun stop() {
        scope.launch { stopInternal() }
    }

    /**
     * Stops, waits until the microphone and player are fully released, then listens again, so
     * changed platform audio settings (e.g. system AEC) apply to the new capture.
     */
    fun restartListening() {
        scope.launch {
            val jobs = listOfNotNull(micJob, turn?.job)
            stopInternal()
            jobs.joinAll()
            micJob = launch { runMic() }
        }
    }

    /** Releases everything; the session can't be used afterwards. */
    fun close() {
        scope.launch { stopInternal() }.invokeOnCompletion { scope.cancel() }
    }

    suspend fun speak(text: String): SpeakResult = speak(TtsRequest(text, language = config.asr.language))

    /**
     * Speaks [request] while continuing to listen. Returns when playback completes, fails, is
     * replaced by another [speak] call, or is interrupted by the user's voice.
     */
    suspend fun speak(request: TtsRequest): SpeakResult {
        val t = withContext(serial) { beginTurn(request) }
        try {
            return t.result.await()
        } catch (e: CancellationException) {
            scope.launch { if (turn === t) interrupt(t, SpeakResult.Interrupted(bargeInUtteranceId = null)) }
            throw e
        }
    }

    fun stopSpeaking() {
        scope.launch { turn?.let { interrupt(it, SpeakResult.Interrupted(bargeInUtteranceId = null)) } }
    }

    fun addMonitor(monitor: DuplexMonitor) {
        scope.launch { if (monitor !in monitors) monitors += monitor }
    }

    fun removeMonitor(monitor: DuplexMonitor) {
        scope.launch { monitors -= monitor }
    }

    // region microphone pipeline

    private suspend fun runMic() {
        val self = currentCoroutineContext().job
        _state.update { it.copy(listening = true) }
        try {
            capture.frames().collect(::onMicChunk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceEvent.Error("capture", e))
        } finally {
            if (micJob === self) {
                micJob = null
                abandonUtterance()
                resetDetection()
                _state.update { it.copy(listening = false) }
            }
        }
    }

    private fun onMicChunk(raw: AudioChunk) {
        val chunk = echoCanceller.process(raw)
        preRoll.write(chunk.samples)
        val playback = isPlaybackActive()
        // The VAD sees every frame to keep its state; too-quiet frames just can't count as speech.
        val probability = vad.process(chunk).let { if (chunk.samples.levelDb() < minSpeechLevelDb) 0f else it }
        monitors.forEach { it.onMicFrame(chunk, probability, playback) }
        val event = detector.update(probability, chunk.durationMs, playback)

        // On Start the pre-roll (which already contains this chunk) is sent instead.
        if (event == SpeechEvent.Start) openUtterance(playback, sincePlayback(config.echoGuardMs)) else utterance?.session?.sendAudio(chunk)

        when (event) {
            SpeechEvent.Confirmed -> utterance?.let {
                it.vadConfirmed = true
                maybeBargeIn(it)
            }
            SpeechEvent.End -> finishUtterance()
            // During or right after playback an unconfirmed blip is most likely echo; otherwise
            // let the recognizer decide so a short word like "好" is never dropped.
            SpeechEvent.FalseAlarm ->
                if (utterance?.let { it.startedDuringPlayback || it.nearPlayback } == true) abandonUtterance() else finishUtterance()
            SpeechEvent.Start, null -> Unit
        }
    }

    private fun isPlaybackActive(): Boolean = turn?.audible == true || sincePlayback(config.echoTailMs)

    /** Whether audible playback ended less than [ms] ago. */
    private fun sincePlayback(ms: Int): Boolean =
        playbackEndedAt?.let { it.elapsedNow() < ms.milliseconds } == true

    private fun resetDetection() {
        detector.reset()
        vad.reset()
        preRoll.clear()
        echoCanceller.reset()
    }

    // endregion

    // region utterances

    private fun openUtterance(duringPlayback: Boolean, nearPlayback: Boolean) {
        val session = try {
            recognizer.startSession(asrConfig)
        } catch (e: Throwable) {
            emit(VoiceEvent.Error("asr", e))
            return
        }
        session.sendAudio(AudioChunk(preRoll.snapshot(), capture.format))

        val u = Utterance(
            id = nextUtteranceId++,
            session = session,
            startedDuringPlayback = duringPlayback,
            nearPlayback = nearPlayback,
            echoReference = if (duringPlayback) turn?.text ?: lastSpokenText else null,
        )
        utterance = u
        if (duringPlayback && config.duckPolicy == DuckPolicy.OnSpeechOnset) duck(u)
        u.job = scope.launch {
            try {
                session.results.collect { onResult(u, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(VoiceEvent.Error("asr", e))
            }
        }
        _state.update { it.copy(userSpeaking = true) }
        emit(VoiceEvent.SpeechStarted(u.id, duringPlayback))
    }

    private fun onResult(u: Utterance, result: AsrResult) {
        val text = result.text.trim()
        if (text.isEmpty()) return

        // While we speak, "嗯"/"哦" means "go on", not "stop". Outside playback it may be an answer.
        val playbackContext = turn != null || u.startedDuringPlayback
        if (playbackContext && !u.bargedIn && backchannelFilter.isBackchannel(text)) {
            if (result.isFinal) emit(VoiceEvent.Backchannel(u.id, text))
            return
        }

        when (echoFilter.judge(text, u.echoReference ?: turn?.text)) {
            EchoVerdict.Echo -> {
                emit(VoiceEvent.EchoSuppressed(u.id, text))
                if (u.ducked && !u.bargedIn) unduck(u)
                return
            }
            EchoVerdict.Undetermined -> if (!(result.isFinal && u.bargedIn)) {
                if (result.isFinal) emit(VoiceEvent.EchoSuppressed(u.id, text))
                return
            }
            EchoVerdict.User -> Unit
        }

        u.hasUserText = true
        if (config.duckPolicy == DuckPolicy.OnUserText) duck(u)
        maybeBargeIn(u)
        emit(if (result.isFinal) VoiceEvent.Final(u.id, text) else VoiceEvent.Partial(u.id, text))
    }

    private fun maybeBargeIn(u: Utterance) {
        val t = turn ?: return
        if (!config.bargeInEnabled || u.bargedIn || !u.vadConfirmed) return
        if (config.bargeInConfirmation == BargeInConfirmation.VadAndUserText && !u.hasUserText) return
        u.bargedIn = true
        interrupt(t, SpeakResult.Interrupted(bargeInUtteranceId = u.id))
        emit(VoiceEvent.BargeIn(u.id, t.id))
    }

    /** The utterance is complete: flush it to the recognizer and wait for the final result. */
    private fun finishUtterance() {
        val u = utterance ?: return
        utterance = null
        u.session.finish()
        if (u.ducked && !u.bargedIn) unduck(u)
        // Everything up to now belongs to this utterance; don't prepend it to the next one.
        preRoll.clear()
        _state.update { it.copy(userSpeaking = false) }
        emit(VoiceEvent.SpeechEnded(u.id, discarded = false))
    }

    /** Drop the utterance; its audio stays in the pre-roll in case speech resumes right away. */
    private fun abandonUtterance() {
        val u = utterance ?: return
        utterance = null
        u.session.cancel()
        u.job?.cancel()
        if (u.ducked && !u.bargedIn) unduck(u)
        _state.update { it.copy(userSpeaking = false) }
        emit(VoiceEvent.SpeechEnded(u.id, discarded = true))
    }

    private fun duck(u: Utterance) {
        if (turn == null || u.ducked || u.bargedIn) return
        u.ducked = true
        player.setDucked(true)
    }

    private fun unduck(u: Utterance) {
        u.ducked = false
        player.setDucked(false)
    }

    // endregion

    // region speaking

    private fun beginTurn(request: TtsRequest): SpeakTurn {
        turn?.let { interrupt(it, SpeakResult.Interrupted(bargeInUtteranceId = null)) }
        val t = SpeakTurn(nextTurnId++, request.text)
        turn = t
        _state.update { it.copy(speaking = true) }
        emit(VoiceEvent.SpeakStarted(t.id, t.text))

        val audio = synthesizer.synthesize(request)
            .onEach { chunk ->
                t.audible = true
                echoCanceller.onPlayback(chunk)
                monitors.forEach { it.onPlayback(chunk) }
            }
            .flowOn(serial)
        val job = scope.launch {
            val result = try {
                player.play(audio)
                SpeakResult.Completed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SpeakResult.Failed(e)
            }
            finishTurn(t, result)
        }
        // Guarantees speak() returns even if the session is closed underneath it.
        job.invokeOnCompletion { t.result.complete(SpeakResult.Interrupted(bargeInUtteranceId = null)) }
        t.job = job
        return t
    }

    private fun finishTurn(t: SpeakTurn, result: SpeakResult) {
        if (!t.result.complete(result)) return
        if (turn === t) {
            turn = null
            lastSpokenText = t.text
            if (t.audible) {
                playbackEndedAt = TimeSource.Monotonic.markNow()
                monitors.forEach { it.onPlaybackStopped() }
            }
            player.setDucked(false)
            _state.update { it.copy(speaking = false) }
        }
        emit(VoiceEvent.SpeakFinished(t.id, result))
    }

    private fun interrupt(t: SpeakTurn, result: SpeakResult) {
        finishTurn(t, result)
        player.stopImmediately()
        t.job?.cancel()
    }

    // endregion

    private fun stopInternal() {
        micJob?.cancel()
        micJob = null
        turn?.let { interrupt(it, SpeakResult.Interrupted(bargeInUtteranceId = null)) }
        abandonUtterance()
        resetDetection()
        _state.update { it.copy(listening = false) }
    }

    private fun emit(event: VoiceEvent) {
        _events.tryEmit(event)
        monitors.forEach { it.onEvent(event) }
    }

    private class Utterance(
        val id: Long,
        val session: AsrSession,
        val startedDuringPlayback: Boolean,
        /** Started within [DuplexConfig.echoGuardMs] after playback. */
        val nearPlayback: Boolean,
        /** What we were saying when this utterance started; used to recognize echo. */
        val echoReference: String?,
    ) {
        var job: Job? = null
        var vadConfirmed = false
        var hasUserText = false
        var bargedIn = false
        var ducked = false
    }

    private class SpeakTurn(val id: Long, val text: String) {
        val result = CompletableDeferred<SpeakResult>()
        var job: Job? = null

        /** First synthesized audio has reached the player. */
        var audible = false
    }
}
