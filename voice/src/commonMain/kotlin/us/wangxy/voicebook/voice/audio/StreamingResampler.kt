package us.wangxy.voicebook.voice.audio

/**
 * Mono resampler for a continuous stream delivered in arbitrary chunks (output doesn't depend on
 * how the input was split). Downsampling averages the input inside each output period, a box
 * low-pass that keeps speech bands clean enough for ASR; upsampling interpolates linearly.
 */
class StreamingResampler(private val fromRate: Int, private val toRate: Int) {
    private val step = fromRate.toDouble() / toRate

    private var sum = 0.0
    private var phase = 0.0

    private var previous = 0f
    private var position = step

    fun process(input: FloatArray, length: Int = input.size): FloatArray = when {
        fromRate == toRate -> input.copyOf(length)
        fromRate > toRate -> downsample(input, length)
        else -> upsample(input, length)
    }

    private fun downsample(input: FloatArray, length: Int): FloatArray {
        val out = FloatArray((length / step).toInt() + 2)
        var n = 0
        for (i in 0 until length) {
            val x = input[i]
            var width = 1.0
            while (width > 0.0) {
                val room = step - phase
                if (width < room) {
                    sum += x * width
                    phase += width
                    width = 0.0
                } else {
                    sum += x * room
                    out[n++] = (sum / step).toFloat()
                    sum = 0.0
                    phase = 0.0
                    width -= room
                }
            }
        }
        return out.copyOf(n)
    }

    private fun upsample(input: FloatArray, length: Int): FloatArray {
        val out = FloatArray(length * kotlin.math.ceil(1 / step).toInt() + 1)
        var n = 0
        for (i in 0 until length) {
            val current = input[i]
            while (position <= 1.0) {
                out[n++] = previous + (current - previous) * position.toFloat()
                position += step
            }
            position -= 1.0
            previous = current
        }
        return out.copyOf(n)
    }
}
