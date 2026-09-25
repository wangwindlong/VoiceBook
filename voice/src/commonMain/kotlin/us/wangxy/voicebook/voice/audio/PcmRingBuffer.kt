package us.wangxy.voicebook.voice.audio

/**
 * Keeps the most recent [capacity] samples of microphone audio so that the beginning of an
 * utterance, which is heard before the VAD fires, can still be sent to the recognizer.
 */
class PcmRingBuffer(val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var writePos = 0

    var size: Int = 0
        private set

    fun write(samples: ShortArray) {
        if (capacity == 0) return
        val n = samples.size
        if (n >= capacity) {
            samples.copyInto(buffer, 0, n - capacity, n)
            writePos = 0
            size = capacity
            return
        }
        val first = minOf(n, capacity - writePos)
        samples.copyInto(buffer, writePos, 0, first)
        if (first < n) samples.copyInto(buffer, 0, first, n)
        writePos = (writePos + n) % capacity
        size = minOf(capacity, size + n)
    }

    /** Returns the last [maxSamples] samples in chronological order. */
    fun snapshot(maxSamples: Int = size): ShortArray {
        val n = minOf(maxSamples, size)
        val out = ShortArray(n)
        if (n == 0) return out
        val start = (writePos - n + capacity) % capacity
        val first = minOf(n, capacity - start)
        buffer.copyInto(out, 0, start, start + first)
        if (first < n) buffer.copyInto(out, first, 0, n - first)
        return out
    }

    fun clear() {
        writePos = 0
        size = 0
    }
}
