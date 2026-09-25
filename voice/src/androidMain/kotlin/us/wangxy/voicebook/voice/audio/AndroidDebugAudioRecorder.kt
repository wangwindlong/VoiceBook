package us.wangxy.voicebook.voice.audio

import android.content.Context
import us.wangxy.voicebook.voice.duplex.VoiceEvent
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes `<external files>/voice_debug/duplex_<time>.wav`: stereo 16-bit, left = microphone after
 * echo cancellation (what VAD and ASR get), right = the TTS reference paced at real time from the
 * moment playback starts. Listen to the left channel: every trace of the right channel in it is
 * echo that reached the recognizer.
 *
 * A `.txt` timeline next to it has one line per mic frame (VAD probability as the detector saw
 * it, level, playback flag) and one per session event, in seconds from the start of the WAV.
 */
internal class AndroidDebugAudioRecorder(private val context: Context?) : DebugAudioRecorder {
    private var file: RandomAccessFile? = null
    private var timeline: PrintWriter? = null
    private var sampleRate = 16_000
    private var dataBytes = 0L
    private val reference = ShortFifo()

    private val seconds: Double get() = dataBytes / 4.0 / sampleRate

    @Synchronized
    override fun start(): String {
        stop()
        val dir = File(context?.getExternalFilesDir(null) ?: error("No context"), "voice_debug").apply { mkdirs() }
        val name = "duplex_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "$name.wav")
        file = RandomAccessFile(out, "rw").apply {
            setLength(0)
            write(ByteArray(44))
        }
        timeline = PrintWriter(File(dir, "$name.txt").bufferedWriter())
        dataBytes = 0
        reference.clear()
        return out.absolutePath
    }

    @Synchronized
    override fun stop() {
        timeline?.close()
        timeline = null
        val f = file ?: return
        file = null
        f.seek(0)
        f.write(wavHeader(dataBytes, sampleRate))
        f.close()
    }

    @Synchronized
    override fun onMicFrame(frame: AudioChunk, vadProbability: Float, playbackActive: Boolean) {
        val f = file ?: return
        sampleRate = frame.format.sampleRate
        timeline?.println(
            "%.3f vad=%.2f level=%.1fdB%s".format(
                Locale.US, seconds, vadProbability, frame.samples.levelDb(), if (playbackActive) " playback" else "",
            ),
        )
        val out = ByteArray(frame.samples.size * 4)
        for (i in frame.samples.indices) {
            val l = frame.samples[i].toInt()
            val r = reference.pollOrZero().toInt()
            out[4 * i] = l.toByte()
            out[4 * i + 1] = (l shr 8).toByte()
            out[4 * i + 2] = r.toByte()
            out[4 * i + 3] = (r shr 8).toByte()
        }
        f.write(out)
        dataBytes += out.size
    }

    @Synchronized
    override fun onPlayback(chunk: AudioChunk) {
        if (file == null) return
        reference.addAll(chunk.samples.resampleLinear(chunk.format.sampleRate, sampleRate))
    }

    @Synchronized
    override fun onPlaybackStopped() {
        reference.clear()
        timeline?.println("%.3f # playback stopped".format(Locale.US, seconds))
    }

    @Synchronized
    override fun onEvent(event: VoiceEvent) {
        timeline?.println("%.3f # %s".format(Locale.US, seconds, event))
    }

    private fun wavHeader(dataLen: Long, rate: Int): ByteArray {
        val channels = 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(dataLen.toInt())
        return header.array()
    }

    private class ShortFifo {
        private var buffer = ShortArray(16_000)
        private var head = 0
        private var size = 0

        fun addAll(samples: ShortArray) {
            if (size + samples.size > buffer.size) {
                val grown = ShortArray(maxOf(buffer.size * 2, size + samples.size))
                for (i in 0 until size) grown[i] = buffer[(head + i) % buffer.size]
                buffer = grown
                head = 0
            }
            for (s in samples) buffer[(head + size++) % buffer.size] = s
        }

        fun pollOrZero(): Short {
            if (size == 0) return 0
            val v = buffer[head]
            head = (head + 1) % buffer.size
            size--
            return v
        }

        fun clear() {
            head = 0
            size = 0
        }
    }
}
