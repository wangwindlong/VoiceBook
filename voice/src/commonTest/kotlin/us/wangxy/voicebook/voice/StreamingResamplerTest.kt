package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.audio.StreamingResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class StreamingResamplerTest {
    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test
    fun chunkingDoesNotChangeOutput() {
        for ((from, to) in listOf(48_000 to 16_000, 44_100 to 16_000, 22_050 to 24_000, 16_000 to 48_000)) {
            val input = sine(440.0, from, 0.5)
            val whole = StreamingResampler(from, to).process(input)
            val chunked = StreamingResampler(from, to).let { r ->
                var pos = 0
                val sizes = intArrayOf(1, 7, 480, 1023, 4096)
                buildList {
                    var k = 0
                    while (pos < input.size) {
                        val n = minOf(sizes[k++ % sizes.size], input.size - pos)
                        addAll(r.process(input.copyOfRange(pos, pos + n)).toList())
                        pos += n
                    }
                }.toFloatArray()
            }
            assertContentEquals(whole, chunked, "$from -> $to")
            val expected = input.size.toLong() * to / from
            assertTrue(abs(whole.size - expected) <= 2, "$from -> $to: ${whole.size} vs $expected samples")
        }
    }

    @Test
    fun keepsSpeechBandAndAttenuatesAliases() {
        val speech = StreamingResampler(48_000, 16_000).process(sine(1_000.0, 48_000, 1.0))
        assertTrue(rms(speech) > 0.33, "1 kHz passes (rms ${rms(speech)})")
        // 15 kHz would alias to 1 kHz at 16 kHz; the box filter must suppress it.
        val alias = StreamingResampler(48_000, 16_000).process(sine(15_000.0, 48_000, 1.0))
        assertTrue(rms(alias) < 0.1, "15 kHz alias attenuated (rms ${rms(alias)})")
    }
}
