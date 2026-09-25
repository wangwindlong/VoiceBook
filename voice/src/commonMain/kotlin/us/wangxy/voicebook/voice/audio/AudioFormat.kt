package us.wangxy.voicebook.voice.audio

/**
 * Interleaved signed 16-bit PCM. This is the only sample encoding used inside the voice module;
 * engines that work with floats or other encodings convert at their boundary.
 */
data class AudioFormat(
    val sampleRate: Int,
    val channels: Int = 1,
) {
    fun samplesFor(durationMs: Int): Int = (sampleRate.toLong() * channels * durationMs / 1000).toInt()

    fun durationMs(samples: Int): Int = (samples * 1000L / (sampleRate.toLong() * channels)).toInt()

    companion object {
        /** The format every ASR engine in this module must accept. */
        val Speech16k = AudioFormat(sampleRate = 16_000, channels = 1)
    }
}

class AudioChunk(
    val samples: ShortArray,
    val format: AudioFormat,
) {
    val durationMs: Int get() = format.durationMs(samples.size)
}
