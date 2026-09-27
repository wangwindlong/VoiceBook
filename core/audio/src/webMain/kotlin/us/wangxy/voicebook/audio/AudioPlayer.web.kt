package us.wangxy.voicebook.audio

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAudioElement

actual fun createAudioPlayer(): AudioPlayer = WebAudioPlayer

/** HTML5 audio element; typed kotlinx-browser bindings, position polled. */
private object WebAudioPlayer : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio: HTMLAudioElement = (document.createElement("audio") as HTMLAudioElement).apply {
        style.display = "none"
    }
    private var ticker: Job? = null

    private val stateFlow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = stateFlow.asStateFlow()

    init {
        audio.addEventListener("error", { _ ->
            stateFlow.update { it.copy(playing = false, error = "播放失败") }
        })
    }

    override fun load(track: AudioTrack, startMs: Long) {
        stop()
        stateFlow.update { AudioState(track = track) }
        audio.src = track.url
        audio.onloadedmetadata = {
            stateFlow.update { it.copy(durationMs = (audio.duration * 1000).toLong()) }
            if (startMs > 0) audio.currentTime = startMs / 1000.0
            audio.play()
            stateFlow.update { it.copy(playing = true) }
            startTicker()
        }
    }

    override fun toggle() {
        if (stateFlow.value.playing) {
            audio.pause()
            stateFlow.update { it.copy(playing = false) }
        } else {
            audio.play()
            stateFlow.update { it.copy(playing = true) }
            startTicker()
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        audio.pause()
        audio.removeAttribute("src")
        stateFlow.update { AudioState(track = stateFlow.value.track) }
    }

    override fun seekTo(fraction: Float) {
        if (audio.duration > 0) audio.currentTime = audio.duration * fraction.coerceIn(0f, 1f)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                stateFlow.update { it.copy(positionMs = (audio.currentTime * 1000).toLong()) }
                delay(500)
            }
        }
    }
}
