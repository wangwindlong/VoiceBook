@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.wangxy.voicebook.audio

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
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.NSURL

actual fun createAudioPlayer(): AudioPlayer = IosAudioPlayer

private object IosAudioPlayer : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: AVPlayer? = null
    private var ticker: Job? = null
    private val stateFlow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = stateFlow.asStateFlow()

    override fun load(track: AudioTrack, startMs: Long) {
        stop()
        stateFlow.update { AudioState(track = track) }
        runCatching {
            val url = NSURL.URLWithString(track.url) ?: return
            val item = AVPlayer(uRL = url)
            if (startMs > 0) {
                CMTimeMakeWithSeconds(startMs / 1000.0, 600)?.let { item.seekToTime(it) }
            }
            player = item
            item.play()
            stateFlow.update {
                it.copy(
                    playing = true,
                    durationMs = item.currentItem?.let { (CMTimeGetSeconds(it.duration) * 1000).toLong() } ?: 0L,
                )
            }
            startTicker()
        }
    }

    override fun toggle() {
        val p = player ?: return
        if (stateFlow.value.playing) {
            p.pause()
            stateFlow.update { it.copy(playing = false) }
        } else {
            p.play()
            stateFlow.update { it.copy(playing = true) }
            startTicker()
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.pause()
        player = null
        stateFlow.update { AudioState(track = it.track) }
    }

    override fun seekTo(fraction: Float) {
        val duration = stateFlow.value.durationMs
        val p = player ?: return
        if (duration <= 0) return
        CMTimeMakeWithSeconds(duration * fraction.coerceIn(0f, 1f) / 1000.0, 600)?.let { p.seekToTime(it) }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val position = (CMTimeGetSeconds(p.currentTime()) * 1000).toLong()
                stateFlow.update {
                    val finished = it.durationMs in 1..position
                    it.copy(positionMs = position, playing = it.playing && !finished)
                }
                delay(500)
            }
        }
    }
}
