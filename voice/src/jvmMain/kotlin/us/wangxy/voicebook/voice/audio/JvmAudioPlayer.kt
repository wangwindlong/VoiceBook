package us.wangxy.voicebook.voice.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat as JvmSoundFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * javax.sound [SourceDataLine] playback. The line is opened lazily per format and kept across
 * [play] calls — re-opening the device per utterance tears the sound pipeline and produces
 * audible clicks. The internal buffer is small on purpose: a blocking [SourceDataLine.write]
 * then stalls for at most ~120 ms, so barge-in cancellation and [stopImmediately] are quick.
 */
internal class JvmAudioPlayer(
    private val duckedVolume: Float = 0.25f,
) : AudioPlayer {
    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var lineFormat: AudioFormat? = null

    @Volatile
    private var ducked = false

    override suspend fun play(chunks: Flow<AudioChunk>) {
        withContext(Dispatchers.IO) {
            chunks.collect { chunk ->
                currentCoroutineContext().ensureActive()
                if (chunk.samples.isNotEmpty()) write(chunk)
            }
            // Suspend until everything written has actually been rendered.
            line?.let { l -> runCatching { l.drain() } }
        }
    }

    override fun stopImmediately() {
        // Dropping the buffered audio unblocks any in-flight blocking write right away.
        line?.let { l -> runCatching { l.flush() } }
    }

    override fun setDucked(ducked: Boolean) {
        this.ducked = ducked
    }

    private fun write(chunk: AudioChunk) {
        val target = lineFor(chunk.format)
        val gain = if (ducked) duckedVolume else 1f
        val bytes = ByteArray(chunk.samples.size * 2)
        for (i in chunk.samples.indices) {
            val v = (chunk.samples[i] * gain).toInt().toShort()
            bytes[i * 2] = (v.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        target.write(bytes, 0, bytes.size)
    }

    private fun lineFor(format: AudioFormat): SourceDataLine {
        val current = line
        if (current != null && lineFormat == format) return current
        current?.let { l -> runCatching { l.close() } }
        val jvmFormat = JvmSoundFormat(format.sampleRate.toFloat(), 16, format.channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, jvmFormat)
        val newLine = (AudioSystem.getLine(info) as SourceDataLine).apply {
            open(jvmFormat, format.sampleRate * format.channels * 2 * 120 / 1000)
            start()
        }
        line = newLine
        lineFormat = format
        return newLine
    }
}
