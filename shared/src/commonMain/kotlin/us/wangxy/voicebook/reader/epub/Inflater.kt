package us.wangxy.voicebook.reader.epub

import kotlinx.io.IOException
import kotlinx.io.Source

/**
 * Whole-buffer RFC 1951 raw deflate decoder in common Kotlin, in the spirit of the voice
 * module's hand-written bzip2 source: the archive already lives in memory, so an entry
 * is inflated in one pass into a growable array — LZ77 back-references then read
 * straight from the output. Bit order is LSB-first, per the deflate spec.
 */
internal object Inflater {

    /** Inflates the complete raw-deflate stream in [input]; [expectedSize] (when >= 0) is verified. */
    fun inflate(input: Source, expectedSize: Int = -1): ByteArray {
        val state = State(input, if (expectedSize > 0) expectedSize else 64)
        state.run()
        if (expectedSize >= 0 && state.written != expectedSize) {
            throw IOException("inflate size mismatch: ${state.written} != $expectedSize")
        }
        return state.out.copyOf(state.written)
    }

    private class State(val input: Source, initial: Int) {
        var out = ByteArray(initial)
        var written = 0

        var bitBuffer = 0
        var bitCount = 0

        val lencode = Huffman()
        val distcode = Huffman()

        fun run() {
            while (true) {
                val last = bits(1)
                when (bits(2)) {
                    0 -> storedBlock()
                    1 -> {
                        fixedTables()
                        inflateBlock()
                    }

                    2 -> {
                        dynamicTables()
                        inflateBlock()
                    }

                    else -> throw IOException("deflate: invalid block type 3")
                }
                if (last == 1) return
            }
        }

        private fun storedBlock() {
            // Discard bits to the byte boundary, then LEN/NLEN as-is.
            bitBuffer = 0
            bitCount = 0
            val len = bits(16)
            val nlen = bits(16) and 0xFFFF
            if (len.toInt() != nlen.toInt().inv() and 0xFFFF) throw IOException("deflate: stored length check failed")
            repeat(len.toInt()) { push(rawByte()) }
        }

        private fun fixedTables() {
            val lengths = IntArray(FixedTableSize)
            var symbol = 0
            while (symbol < 144) lengths[symbol++] = 8
            while (symbol < 256) lengths[symbol++] = 9
            while (symbol < 280) lengths[symbol++] = 7
            while (symbol < FixedTableSize) lengths[symbol++] = 8
            build(lengths, FixedTableSize, lencode)
            // Distance code lengths: all five bits; 30 and 31 are present but invalid when used.
            for (i in 0 until 30 + 2) lengths[i] = 5
            build(lengths, 30 + 2, distcode)
        }

        private fun dynamicTables() {
            val lengths = IntArray(FixedTableSize + 32)
            val literalCount = bits(5) + 257
            val distanceCount = bits(5) + 1
            val codeCount = bits(4) + 4
            if (literalCount > FixedTableSize || distanceCount > 32) throw IOException("deflate: too many codes")

            val clLengths = IntArray(19)
            for (i in 0 until codeCount) clLengths[CodeLengthOrder[i]] = bits(3)
            build(clLengths, 19, lencode /* reused temporarily as the code-length code */)

            val clcode = lencode
            var index = 0
            while (index < literalCount + distanceCount) {
                val symbol = decode(clcode)
                when {
                    symbol < 16 -> lengths[index++] = symbol

                    symbol == 16 -> {
                        if (index == 0) throw IOException("deflate: repeat with no previous length")
                        val previous = lengths[index - 1]
                        repeat(3 + bits(2)) { if (index < lengths.size) lengths[index++] = previous }
                    }

                    symbol == 17 -> repeat(3 + bits(3)) { if (index < lengths.size) lengths[index++] = 0 }

                    else -> repeat(11 + bits(7)) { if (index < lengths.size) lengths[index++] = 0 }
                }
            }
            if (lengths[256] == 0) throw IOException("deflate: missing end-of-block code")
            build(lengths, literalCount, lencode)
            build(lengths.copyOfRange(literalCount, literalCount + distanceCount), distanceCount, distcode)
        }

        private fun inflateBlock() {
            while (true) {
                val symbol = decode(lencode)
                if (symbol < 256) {
                    push(symbol)
                    continue
                }
                if (symbol == 256) return
                val li = symbol - 257
                if (li >= LengthBase.size) throw IOException("deflate: invalid length symbol $symbol")
                var length = LengthBase[li] + bits(LengthExtra[li])

                val ds = decode(distcode)
                if (ds >= DistBase.size) throw IOException("deflate: invalid distance symbol $ds")
                val distance = DistBase[ds] + bits(DistExtra[ds])
                if (distance > written) throw IOException("deflate: distance too far back")

                var from = written - distance
                repeat(length) {
                    push(out[from++].toInt() and 0xFF)
                }
            }
        }

        private fun push(byte: Int) {
            if (written == out.size) out = out.copyOf(out.size * 2)
            out[written++] = byte.toByte()
        }

        private fun rawByte(): Int {
            if (input.exhausted()) throw IOException("deflate: unexpected end of input")
            return input.readByte().toInt() and 0xFF
        }

        private fun bits(need: Int): Int {
            while (bitCount < need) {
                bitBuffer = bitBuffer or ((rawByte() shl bitCount))
                bitCount += 8
            }
            val value = bitBuffer and ((1 shl need) - 1)
            bitBuffer = bitBuffer ushr need
            bitCount -= need
            return value
        }

        /** Canonical bit-at-a-time decode, per puff.c. */
        private fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or bits(1)
                val count = h.count[len]
                if (code - count < first) return h.symbol[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw IOException("deflate: bad code")
        }
    }

    /** Canonical Huffman code, decoded bit-by-bit the way puff.c does it. */
    private class Huffman {
        val count = IntArray(16)
        val symbol = IntArray(FixedTableSize + 32)
    }

    private fun build(lengths: IntArray, count: Int, h: Huffman) {
        h.count.fill(0)
        for (i in 0 until count) h.count[lengths[i]]++
        h.count[0] = 0

        var left = 1
        for (len in 1..15) {
            left = left shl 1
            left -= h.count[len]
            if (left < 0) throw IOException("deflate: over-subscribed huffman code")
        }

        val offsets = IntArray(17)
        for (len in 1..15) offsets[len + 1] = offsets[len] + h.count[len]
        for (i in 0 until count) {
            if (lengths[i] != 0) h.symbol[offsets[lengths[i]]++] = i
        }
    }

    private val FixedTableSize = 288

    private val CodeLengthOrder = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val LengthBase = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LengthExtra = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DistBase = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DistExtra = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
}
