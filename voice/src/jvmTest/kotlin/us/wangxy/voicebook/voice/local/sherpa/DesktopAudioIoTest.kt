package us.wangxy.voicebook.voice.local.sherpa

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import us.wangxy.voicebook.voice.audio.AudioChunk
import us.wangxy.voicebook.voice.audio.AudioFormat
import us.wangxy.voicebook.voice.audio.JvmAudioCapture
import us.wangxy.voicebook.voice.audio.JvmAudioPlayer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop audio IO acceptance against the real sound devices (PipeWire/ALSA): records half a
 * second from the microphone and plays a one second tone (audible). Requires a working output
 * and input; fails loudly when the machine has no audio stack.
 */
class DesktopAudioIoTest {

    @Test
    fun captureDeliversFrames() = runBlocking {
        val capture = JvmAudioCapture()
        val chunks = withTimeout(30_000) { capture.frames().take(25).toList() } // ~0.5 s
        assertEquals(25, chunks.size, "expected 25 frames of 20 ms each")
        assertTrue(chunks.all { it.samples.size == it.format.samplesFor(20) }, "frame size mismatch")
        val peak = chunks.maxOf { c -> c.samples.maxOf { abs(it.toInt()) } }
        println("[verify] capture: ${chunks.size} frames, peak amplitude $peak (${chunks.first().format})")
    }

    @Test
    fun playerPlaysToneUntilRendered() = runBlocking {
        val player = JvmAudioPlayer()
        val fmt = AudioFormat.Speech16k
        val tone = ShortArray(fmt.sampleRate) { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / fmt.sampleRate) * 6000).toInt().toShort()
        }
        val start = System.currentTimeMillis()
        player.play(flow { emit(AudioChunk(tone, fmt)) })
        val elapsed = System.currentTimeMillis() - start
        println("[verify] player: 1 s tone rendered in $elapsed ms")
        assertTrue(elapsed >= 900, "play() must suspend until rendered, returned after ${elapsed} ms")
    }
}
