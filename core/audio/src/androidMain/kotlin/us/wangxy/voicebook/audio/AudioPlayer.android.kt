package us.wangxy.voicebook.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
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

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer

private object AndroidAudioPlayer : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val stateFlow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = stateFlow.asStateFlow()

    override fun load(track: AudioTrack, startMs: Long) {
        stop()
        stateFlow.update { AudioState(track = track) }
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            mp.setDataSource(track.url)
            mp.setOnPreparedListener {
                if (startMs > 0) mp.seekTo(startMs.toInt())
                mp.start()
                stateFlow.update { it.copy(playing = true, durationMs = mp.duration.toLong()) }
                startTicker()
            }
            mp.setOnErrorListener { _, what, extra ->
                stateFlow.update { it.copy(playing = false, error = "播放失败 ($what/$extra)") }
                true
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            stateFlow.update { it.copy(error = it.error ?: "播放失败") }
        }
    }

    override fun toggle() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                stateFlow.update { it.copy(playing = false) }
            } else {
                mp.start()
                stateFlow.update { it.copy(playing = true) }
                startTicker()
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        runCatching { player?.release() }
        player = null
        stateFlow.update { AudioState(track = it.track) }
    }

    override fun seekTo(fraction: Float) {
        val mp = player ?: return
        runCatching { mp.seekTo((mp.duration * fraction.coerceIn(0f, 1f)).toInt()) }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                stateFlow.update {
                    it.copy(positionMs = mp.currentPosition.toLong(), durationMs = mp.duration.toLong())
                }
                delay(500)
            }
        }
    }
}
