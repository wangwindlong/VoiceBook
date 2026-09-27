package us.wangxy.voicebook.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import us.wangxy.voicebook.voice.audio.AudioProcessingSettings
import us.wangxy.voicebook.voice.audio.DebugAudioRecorder
import us.wangxy.voicebook.voice.duplex.DuplexProbe
import us.wangxy.voicebook.voice.duplex.DuplexReport
import us.wangxy.voicebook.voice.duplex.DuplexState
import us.wangxy.voicebook.voice.duplex.DuplexVoiceSession
import us.wangxy.voicebook.voice.duplex.VoiceEvent
import us.wangxy.voicebook.voice.model.ModelManager
import us.wangxy.voicebook.voice.model.ModelSetupState
import us.wangxy.voicebook.voice.routing.RoutingPolicy
import us.wangxy.voicebook.voice.routing.VoiceRoutingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Long enough to measure echo over ~20 s of playback. */
const val EchoTestText =
    "现在开始回声测试。请保持安静，不要说话。这段话会持续播放大约二十秒，" +
        "用来检查语音播报的声音会不会被麦克风录进去，并被误识别成用户说的话。" +
        "如果回声消除正常工作，播报期间不应该出现识别结果，也不应该触发打断。" +
        "测试结束后，请查看屏幕上的回声泄漏分贝数和误触发次数。谢谢你的配合。"

class VoiceViewModel(
    private val session: DuplexVoiceSession,
    private val routing: VoiceRoutingSettings,
    private val audioSettings: AudioProcessingSettings,
    private val models: ModelManager,
    private val recorder: DebugAudioRecorder?,
) : ViewModel() {
    val modelState: StateFlow<ModelSetupState> = models.state
    val state: StateFlow<DuplexState> = session.state
    val asrPolicy: StateFlow<RoutingPolicy> = routing.asrPolicy
    val ttsPolicy: StateFlow<RoutingPolicy> = routing.ttsPolicy
    val systemAec: StateFlow<Boolean> = audioSettings.systemAec

    private val probe = DuplexProbe()
    val report: StateFlow<DuplexReport> = probe.report

    private val _recordingPath = MutableStateFlow<String?>(null)
    val recordingPath: StateFlow<String?> = _recordingPath.asStateFlow()
    val canRecord: Boolean = recorder != null

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    init {
        models.ensureModels()
        session.addMonitor(probe)
        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is VoiceEvent.Partial -> _partial.value = event.text
                    is VoiceEvent.Final, is VoiceEvent.SpeechEnded -> _partial.value = ""
                    else -> Unit
                }
                describe(event)?.let { line -> _log.update { (listOf(line) + it).take(200) } }
            }
        }
    }

    fun toggleListening() {
        if (state.value.listening) session.stop() else session.start()
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { session.speak(text) }
    }

    /** Resets the counters and plays a long prompt; the user should stay silent. */
    fun runEchoTest() {
        probe.reset()
        if (!state.value.listening) session.start()
        speak(EchoTestText)
    }

    fun resetReport() = probe.reset()

    fun retryModelDownload() = models.ensureModels()

    fun stopSpeaking() = session.stopSpeaking()

    fun setAsrPolicy(policy: RoutingPolicy) {
        routing.asrPolicy.value = policy
    }

    fun setTtsPolicy(policy: RoutingPolicy) {
        routing.ttsPolicy.value = policy
    }

    /** Takes effect the next time listening starts; restarts listening if it is running. */
    fun setSystemAec(enabled: Boolean) {
        audioSettings.systemAec.value = enabled
        if (state.value.listening) session.restartListening()
        probe.reset()
    }

    fun toggleRecording() {
        val r = recorder ?: return
        if (_recordingPath.value == null) {
            _recordingPath.value = r.start()
            session.addMonitor(r)
        } else {
            session.removeMonitor(r)
            r.stop()
            _log.update { listOf("调试录音已保存: ${_recordingPath.value}") + it }
            _recordingPath.value = null
        }
    }

    override fun onCleared() {
        recorder?.let {
            session.removeMonitor(it)
            it.stop()
        }
        session.close()
    }

    private fun describe(event: VoiceEvent): String? = when (event) {
        is VoiceEvent.Partial -> null
        is VoiceEvent.Final -> "识别: ${event.text}"
        is VoiceEvent.BargeIn -> "打断播报 (utterance ${event.utteranceId})"
        is VoiceEvent.EchoSuppressed -> "忽略回声: ${event.text}"
        is VoiceEvent.Backchannel -> "口头禅, 不打断: ${event.text}"
        is VoiceEvent.SpeakStarted -> "播报: ${event.text.take(30)}"
        is VoiceEvent.SpeakFinished -> "播报结束: ${event.result}"
        is VoiceEvent.SpeechStarted -> if (event.duringPlayback) "检测到说话 (播报中)" else "检测到说话"
        is VoiceEvent.SpeechEnded -> if (event.discarded) "误触发, 已丢弃" else null
        is VoiceEvent.Error -> "错误 [${event.source}]: ${event.cause.message}"
    }
}
