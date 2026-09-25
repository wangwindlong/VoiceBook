package us.wangxy.voicebook.voice.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.sound.sampled.AudioFormat as JvmSoundFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * javax.sound [TargetDataLine] capture. [frames] is a cold flow: collecting opens the microphone,
 * cancelling closes it (within one 20 ms frame, since [TargetDataLine.read] returns per frame).
 * Capturing keeps running while the player is playing — the desktop mix is full duplex.
 */
internal class JvmAudioCapture(
    override val format: AudioFormat = AudioFormat.Speech16k,
    private val frameMs: Int = 20,
) : AudioCapture {
    /**
     * Desktop mixers apply no AEC of their own, except on Linux where PipeWire/PulseAudio's
     * module-echo-cancel virtual source (embedded WebRTC AEC) may be selected. When it is not
     * available, use headphones or plug a software EchoCanceller into the session.
     */
    override val hasPlatformEchoCancellation: Boolean by lazy { hasEchoCancelSource() }

    override fun frames(): Flow<AudioChunk> = flow {
        val frameSamples = format.samplesFor(frameMs)
        val jvmFormat = JvmSoundFormat(format.sampleRate.toFloat(), 16, format.channels, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, jvmFormat)
        val bufferSize = format.sampleRate * format.channels * 2
        val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
            // ~1 s buffer, so a stalled consumer never makes the driver drop audio.
            open(jvmFormat, bufferSize)
            start()
        }
        try {
            val bytes = ByteArray(frameSamples * 2)
            while (currentCoroutineContext().isActive) {
                val n = line.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val samples = ShortArray(n / 2) { i ->
                    ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                emit(AudioChunk(samples, format))
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun hasEchoCancelSource(): Boolean = runCatching {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return@runCatching false
        val process = ProcessBuilder("pactl", "list", "sources", "short").start()
        val out = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        out.lineSequence().any { it.contains("echo-cancel", ignoreCase = true) }
    }.getOrDefault(false)
}
