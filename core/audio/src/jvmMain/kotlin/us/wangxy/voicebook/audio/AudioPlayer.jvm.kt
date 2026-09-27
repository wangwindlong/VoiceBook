package us.wangxy.voicebook.audio

import javazoom.jl.player.FactoryRegistry
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
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
import java.net.URL

actual fun createAudioPlayer(): AudioPlayer = DesktopAudioPlayer

/**
 * JLayer MP3 playback. v1: play/pause/stop — pause releases the stream and resume
 * restarts it from the beginning (no seek); progress reflects elapsed play time.
 * Non-MP3 formats report an error. See the interface doc for the trade-off.
 */
private object DesktopAudioPlayer : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: AdvancedPlayer? = null
    private var ticker: Job? = null
    private var elapsedMs = 0L
    private var trackStartMs = 0L
    private var stopped = false

    private val stateFlow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = stateFlow.asStateFlow()

    override fun load(track: AudioTrack, startMs: Long) {
        stop()
        stateFlow.update { AudioState(track = track) }
        stopped = false
        elapsedMs = startMs
        trackStartMs = System.currentTimeMillis() - startMs
        val url = runCatching { URL(track.url) }.getOrNull()
        if (url == null) {
            stateFlow.update { it.copy(error = "无效的音频地址") }
            return
        }
        playbackThread = Thread {
            runCatching {
                val device = FactoryRegistry.systemRegistry().createAudioDevice()
                val p = AdvancedPlayer(url.openStream(), device)
                p.setPlayBackListener(object : PlaybackListener() {
                    override fun playbackFinished(event: PlaybackEvent) {
                        stateFlow.update { it.copy(playing = false) }
                    }
                })
                player = p
                p.play()
                if (!stopped) stateFlow.update { it.copy(playing = false) }
            }.onFailure { throwable ->
                stateFlow.update { state -> state.copy(playing = false, error = throwable.message ?: "播放失败（桌面端仅支持 MP3）") }
            }
        }.apply {
            isDaemon = true
            start()
        }
        stateFlow.update { it.copy(playing = true) }
        startTicker()
    }

    override fun toggle() {
        if (stateFlow.value.playing) {
            pause()
        } else {
            stateFlow.value.track?.let { load(it, elapsedMs) }
        }
    }

    private fun pause() {
        stopped = true
        runCatching { player?.stop() }
        player = null
        ticker?.cancel()
        ticker = null
        stateFlow.update { it.copy(playing = false) }
    }

    override fun stop() {
        stopped = true
        runCatching { player?.stop() }
        player = null
        ticker?.cancel()
        ticker = null
        stateFlow.update { AudioState(track = stateFlow.value.track) }
    }

    override fun seekTo(fraction: Float) {
        // JLayer has no seek; restart approximates nothing useful, so ignore.
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                if (stateFlow.value.playing) {
                    elapsedMs = System.currentTimeMillis() - trackStartMs
                    stateFlow.update { it.copy(positionMs = elapsedMs) }
                }
                delay(500)
            }
        }
    }

    private var playbackThread: Thread? = null
}
