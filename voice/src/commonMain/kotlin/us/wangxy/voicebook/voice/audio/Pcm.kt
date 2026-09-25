package us.wangxy.voicebook.voice.audio

/** RMS level in dBFS; -120 for empty or all-zero input. */
fun ShortArray.levelDb(): Float {
    if (isEmpty()) return -120f
    var sum = 0.0
    for (s in this) sum += s.toDouble() * s
    val rms = kotlin.math.sqrt(sum / size) / 32768.0
    return if (rms <= 1e-6) -120f else (20 * kotlin.math.log10(rms)).toFloat()
}

fun ShortArray.toFloatPcm(): FloatArray = FloatArray(size) { this[it] / 32768f }

fun FloatArray.toShortPcm(): ShortArray = ShortArray(size) {
    (this[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
}

/** Linear-interpolation resampling of mono PCM; adequate for diagnostics and echo references. */
fun ShortArray.resampleLinear(fromRate: Int, toRate: Int): ShortArray {
    if (fromRate == toRate || isEmpty()) return this
    val outSize = (size.toLong() * toRate / fromRate).toInt()
    val step = fromRate.toDouble() / toRate
    return ShortArray(outSize) { i ->
        val pos = i * step
        val index = pos.toInt()
        val next = minOf(index + 1, size - 1)
        val frac = pos - index
        (this[index] * (1 - frac) + this[next] * frac).toInt().toShort()
    }
}

fun ShortArray.toLittleEndianBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt()
        out[2 * i] = (v and 0xFF).toByte()
        out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * Converts a PCM16LE byte stream that may be split at arbitrary (odd) byte boundaries.
 */
class Pcm16LeDecoder {
    private var pendingLowByte: Int = -1

    fun decode(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        var index = 0
        val total = length + if (pendingLowByte >= 0) 1 else 0
        val out = ShortArray(total / 2)
        var outIndex = 0
        if (pendingLowByte >= 0 && length > 0) {
            out[outIndex++] = (pendingLowByte or (bytes[0].toInt() shl 8)).toShort()
            pendingLowByte = -1
            index = 1
        }
        while (index + 1 < length) {
            val lo = bytes[index].toInt() and 0xFF
            val hi = bytes[index + 1].toInt()
            out[outIndex++] = (lo or (hi shl 8)).toShort()
            index += 2
        }
        if (index < length) pendingLowByte = bytes[index].toInt() and 0xFF
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }
}
