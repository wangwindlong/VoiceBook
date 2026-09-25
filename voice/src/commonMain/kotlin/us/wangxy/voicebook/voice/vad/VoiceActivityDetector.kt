package us.wangxy.voicebook.voice.vad

import us.wangxy.voicebook.voice.audio.AudioChunk
import kotlin.math.log10
import kotlin.math.sqrt

interface VoiceActivityDetector {
    /** Speech probability in [0, 1] for [chunk]. Called for every microphone frame, in order. */
    fun process(chunk: AudioChunk): Float

    fun reset()
}

/**
 * Dependency-free fallback VAD: loudness above an adaptive noise floor.
 * Good enough for development; use the Silero VAD from sherpa-onnx in production.
 */
class EnergyVad(
    /** Level above the noise floor (dB) that maps to probability 0.5. */
    private val marginDb: Float = 10f,
    /** dB range mapped onto the probability slope around [marginDb]. */
    private val slopeDb: Float = 10f,
    private val minFloorDb: Float = -70f,
) : VoiceActivityDetector {
    private var noiseFloorDb = -50f

    override fun process(chunk: AudioChunk): Float {
        val db = levelDb(chunk.samples)
        noiseFloorDb += if (db < noiseFloorDb) (db - noiseFloorDb) * 0.2f else (db - noiseFloorDb) * 0.005f
        noiseFloorDb = noiseFloorDb.coerceAtLeast(minFloorDb)
        return ((db - noiseFloorDb - marginDb) / slopeDb + 0.5f).coerceIn(0f, 1f)
    }

    override fun reset() {
        noiseFloorDb = -50f
    }

    private fun levelDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return minFloorDb
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size) / 32768.0
        return (20 * log10(rms + 1e-9)).toFloat()
    }
}
