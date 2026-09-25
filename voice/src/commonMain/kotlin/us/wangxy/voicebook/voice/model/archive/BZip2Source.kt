package us.wangxy.voicebook.voice.model.archive

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source

/**
 * Streaming bzip2 decompressor in common Kotlin, so model archives unpack identically on Android
 * and iOS (which has no commons-compress). Verifies block and stream CRCs; concatenated streams
 * are decoded, anything else after the first stream is ignored.
 */
internal class BZip2Source(private val input: Source) : RawSource {
    private var bitBuffer = 0L
    private var bitCount = 0

    private var blockSize = 0
    private var tt = IntArray(0)

    private var started = false
    private var finished = false
    private var inBlock = false

    // Current block's inverse-BWT walk and run-length (RLE1) state.
    private var tPos = 0
    private var remaining = 0
    private var lastByte = -1
    private var runCount = 0
    private var repeat = 0
    private var repeatByte = 0

    private var crc = -1
    private var expectedBlockCrc = 0
    private var combinedCrc = 0

    // Huffman tables of the selector group currently in use.
    private var limit = IntArray(0)
    private var base = IntArray(0)
    private var perm = IntArray(0)
    private var minLen = 0

    private val out = ByteArray(OutChunk)

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        if (!started) {
            if (!startStream(first = true)) throw IOException("Empty bzip2 input")
            started = true
        }
        val max = minOf(byteCount, out.size.toLong()).toInt()
        var n = 0
        while (n < max && !finished) {
            if (repeat > 0) {
                repeat--
                n = emit(repeatByte, n)
                continue
            }
            if (remaining == 0) {
                if (inBlock) endBlock()
                inBlock = nextBlock()
                if (!inBlock) finished = true
                continue
            }
            val t = tt[tPos]
            val b = t and 0xFF
            tPos = t ushr 8
            remaining--
            if (runCount == 4) {
                // After four equal bytes the next symbol is a repeat count for that byte.
                repeat = b
                repeatByte = lastByte
                runCount = 0
                lastByte = -1
                continue
            }
            if (b == lastByte) runCount++ else {
                lastByte = b
                runCount = 1
            }
            n = emit(b, n)
        }
        if (n == 0) return -1
        sink.write(out, 0, n)
        return n.toLong()
    }

    override fun close() = input.close()

    private fun emit(b: Int, n: Int): Int {
        out[n] = b.toByte()
        crc = (crc shl 8) xor CrcTable[((crc ushr 24) xor b) and 0xFF]
        return n + 1
    }

    private fun endBlock() {
        val blockCrc = crc.inv()
        if (blockCrc != expectedBlockCrc) throw IOException("bzip2 block CRC mismatch")
        combinedCrc = ((combinedCrc shl 1) or (combinedCrc ushr 31)) xor blockCrc
    }

    /** Reads the stream header. Returns false at a clean end of input. */
    private fun startStream(first: Boolean): Boolean {
        if (input.exhausted()) return false
        val magic = bits(24)
        val level = bits(8) - '0'.code
        if (magic != 0x425A68 || level !in 1..9) {
            if (first) throw IOException("Not a bzip2 stream")
            return false
        }
        blockSize = level * 100_000
        if (tt.size < blockSize) tt = IntArray(blockSize)
        combinedCrc = 0
        return true
    }

    /** Positions on the next block's data. Returns false at the end of all streams. */
    private fun nextBlock(): Boolean {
        while (true) {
            val hi = bits(24)
            val lo = bits(24)
            when {
                hi == 0x314159 && lo == 0x265359 -> {
                    readBlock()
                    return true
                }
                hi == 0x177245 && lo == 0x385090 -> {
                    if (bits(32) != combinedCrc) throw IOException("bzip2 stream CRC mismatch")
                    bitCount = 0 // padding to the byte boundary
                    if (!startStream(first = false)) return false
                }
                else -> throw IOException("Corrupt bzip2 block header")
            }
        }
    }

    private fun readBlock() {
        expectedBlockCrc = bits(32)
        if (bit() != 0) throw IOException("Randomised bzip2 blocks are not supported")
        val origPtr = bits(24)

        val seqToUnseq = IntArray(256)
        var numInUse = 0
        val inUse16 = bits(16)
        for (i in 0 until 16) {
            if (inUse16 and (0x8000 ushr i) == 0) continue
            val used = bits(16)
            for (j in 0 until 16) if (used and (0x8000 ushr j) != 0) seqToUnseq[numInUse++] = i * 16 + j
        }
        if (numInUse == 0) throw IOException("Corrupt bzip2 block: empty symbol map")
        val alphaSize = numInUse + 2

        val groups = bits(3)
        val selectorCount = bits(15)
        if (groups !in 2..6 || selectorCount < 1) throw IOException("Corrupt bzip2 block: bad Huffman groups")
        val order = IntArray(groups) { it }
        val selectors = ByteArray(selectorCount)
        for (i in 0 until selectorCount) {
            var j = 0
            while (bit() == 1) if (++j >= groups) throw IOException("Corrupt bzip2 block: bad selector")
            val g = order[j]
            order.copyInto(order, 1, 0, j)
            order[0] = g
            selectors[i] = g.toByte()
        }

        val tables = Array(groups) {
            val lengths = IntArray(alphaSize)
            var len = bits(5)
            for (s in 0 until alphaSize) {
                while (true) {
                    if (len !in 1..MaxCodeLen) throw IOException("Corrupt bzip2 block: bad code length")
                    if (bit() == 0) break
                    len += if (bit() == 0) 1 else -1
                }
                lengths[s] = len
            }
            HuffmanTable(lengths)
        }

        val mtf = IntArray(256) { it }
        val counts = IntArray(256)
        val eob = numInUse + 1
        var n = 0
        var group = -1
        var groupLeft = 0
        var run = 0
        var runWeight = 1
        while (true) {
            if (groupLeft == 0) {
                if (++group >= selectorCount) throw IOException("Corrupt bzip2 block: selectors exhausted")
                val t = tables[selectors[group].toInt()]
                limit = t.limit
                base = t.base
                perm = t.perm
                minLen = t.minLen
                groupLeft = GroupSize
            }
            groupLeft--
            val sym = nextSymbol()
            if (sym <= 1) {
                // RUNA/RUNB: bijective base-2 run length of the byte at the MTF front.
                run += (sym + 1) * runWeight
                runWeight = runWeight shl 1
                if (run > blockSize) throw IOException("Corrupt bzip2 block: run too long")
                continue
            }
            if (run > 0) {
                if (n + run > blockSize) throw IOException("Corrupt bzip2 block: overflow")
                val b = seqToUnseq[mtf[0]]
                counts[b] += run
                tt.fill(b, n, n + run)
                n += run
                run = 0
                runWeight = 1
            }
            if (sym == eob) break
            val index = sym - 1
            val v = mtf[index]
            mtf.copyInto(mtf, 1, 0, index)
            mtf[0] = v
            if (n >= blockSize) throw IOException("Corrupt bzip2 block: overflow")
            val b = seqToUnseq[v]
            counts[b]++
            tt[n++] = b
        }
        if (origPtr !in 0 until n) throw IOException("Corrupt bzip2 block: bad origin pointer")

        // Inverse BWT: link each position to its successor in the upper 24 bits.
        val next = IntArray(256)
        var sum = 0
        for (i in 0 until 256) {
            next[i] = sum
            sum += counts[i]
        }
        for (i in 0 until n) {
            val b = tt[i] and 0xFF
            tt[next[b]] = tt[next[b]] or (i shl 8)
            next[b]++
        }
        tPos = tt[origPtr] ushr 8
        remaining = n
        lastByte = -1
        runCount = 0
        repeat = 0
        crc = -1
    }

    private fun nextSymbol(): Int {
        var len = minLen
        var code = bits(len)
        while (len <= MaxCodeLen) {
            if (code <= limit[len]) {
                val index = code - base[len]
                if (index !in perm.indices) break
                return perm[index]
            }
            len++
            code = (code shl 1) or bit()
        }
        throw IOException("Corrupt bzip2 block: bad Huffman code")
    }

    private fun bit(): Int = bits(1)

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            if (input.exhausted()) throw IOException("Unexpected end of bzip2 data")
            bitBuffer = (bitBuffer shl 8) or (input.readByte().toLong() and 0xFF)
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuffer ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    /** Canonical Huffman decode tables in the layout of bzip2's `hbCreateDecodeTables`. */
    private class HuffmanTable(lengths: IntArray) {
        val minLen = lengths.min()
        private val maxLen = lengths.max()
        val limit = IntArray(MaxCodeLen + 2) { Int.MIN_VALUE }
        val base = IntArray(MaxCodeLen + 2)
        val perm = IntArray(lengths.size)

        init {
            var p = 0
            for (len in minLen..maxLen) for (s in lengths.indices) if (lengths[s] == len) perm[p++] = s
            for (len in lengths) base[len + 1]++
            for (i in 1 until base.size) base[i] += base[i - 1]
            var code = 0
            for (len in minLen..maxLen) {
                code += base[len + 1] - base[len]
                limit[len] = code - 1
                code = code shl 1
            }
            for (len in maxLen downTo minLen + 1) base[len] = ((limit[len - 1] + 1) shl 1) - base[len]
        }
    }

    private companion object {
        const val MaxCodeLen = 20
        const val GroupSize = 50
        const val OutChunk = 64 * 1024

        val CrcTable = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c and (1 shl 31) != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }
    }
}
