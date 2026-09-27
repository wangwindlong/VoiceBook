package us.wangxy.voicebook.reader.epub

import kotlinx.io.Buffer
import kotlinx.io.IOException

/**
 * Read-only ZIP reader over an in-memory archive, sized for EPUB files: parses the
 * end-of-central-directory + central directory, then decompresses individual entries on
 * demand (method 0 stored / 8 deflate via [Inflater]). Zip64 and encrypted entries are
 * rejected — well-formed EPUBs need neither.
 */
internal class ZipReader(private val bytes: ByteArray) {

    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Int,
    )

    private val entries = LinkedHashMap<String, Entry>()

    init {
        parseCentralDirectory()
    }

    fun entryNames(): List<String> = entries.keys.filterNot { it.endsWith("/") }

    fun contains(name: String): Boolean = entries.containsKey(name)

    fun read(name: String): ByteArray {
        val entry = entries[name] ?: throw IOException("zip: no entry $name")
        val local = entry.localOffset
        if (u32(local) != LocalHeaderSig) throw IOException("zip: bad local header for $name")

        val nameLen = u16(local + 26)
        val extraLen = u16(local + 28)
        val dataStart = local + 30 + nameLen + extraLen
        val dataEnd = dataStart + entry.compressedSize.toInt()
        if (entry.compressedSize > bytes.size || dataEnd > bytes.size) {
            throw IOException("zip: entry $name overruns archive")
        }
        return when (entry.method) {
            0 -> bytes.copyOfRange(dataStart, dataEnd)
            8 -> {
                val input = Buffer()
                input.write(bytes, dataStart, dataEnd)
                Inflater.inflate(input, entry.uncompressedSize.toInt())
            }

            else -> throw IOException("zip: unsupported compression method ${entry.method} for $name")
        }
    }

    private fun parseCentralDirectory() {
        val eocd = findEocd() ?: throw IOException("zip: end-of-central-directory not found")
        val count = u16(eocd + 10)
        val cdSize = u32(eocd + 12)
        var offset = u32(eocd + 16)
        if (offset == Zip64Marker || cdSize == Zip64Marker ||
            offset < 0 || offset + 4 > bytes.size || u32(offset.toInt()) != CentralHeaderSig
        ) {
            // Either a zip64 marker (unsupported) or a prepended stub shifted the directory;
            // try to resync via the recorded size: dirStart + cdSize tail == eocd.
            val candidate = eocd.toLong() - cdSize
            if (candidate < 0 || candidate + 4 > bytes.size || u32(candidate.toInt()) != CentralHeaderSig) {
                throw IOException("zip: central directory not found")
            }
            offset = candidate
        }

        repeat(count) {
            if (u32(offset.toInt()) != CentralHeaderSig) throw IOException("zip: broken central directory")
            val flags = u16(offset.toInt() + 8)
            if (flags and 0x1 != 0) throw IOException("zip: encrypted entries unsupported")
            val method = u16(offset.toInt() + 10)
            val compressed = u32(offset.toInt() + 20)
            val uncompressed = u32(offset.toInt() + 24)
            if (compressed == Zip64Marker || uncompressed == Zip64Marker) {
                throw IOException("zip: zip64 entries unsupported")
            }
            val nameLen = u16(offset.toInt() + 28)
            val extraLen = u16(offset.toInt() + 30)
            val commentLen = u16(offset.toInt() + 32)
            val localOffset = u32(offset.toInt() + 42)
            val nameStart = offset.toInt() + 46
            val name = bytes.decodeToString(nameStart, nameStart + nameLen)
            if (!name.endsWith("/") && localOffset + 4 <= bytes.size) {
                entries[name] = Entry(name, method, compressed, uncompressed, localOffset.toInt())
            }
            offset += 46L + nameLen + extraLen + commentLen
        }
    }

    private fun findEocd(): Int {
        // EOCD is at least 22 bytes from the end; its signature may be preceded by a file
        // comment of up to 64K, so scan backwards for PK\x05\x06.
        var i = bytes.size - 22
        val floor = maxOf(0, i - 0xFFFF)
        while (i >= floor) {
            if (u32(i) == EocdSig) return i
            i--
        }
        return -1
    }

    private fun u16(at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private companion object {
        const val EocdSig = 0x06054b50L
        const val CentralHeaderSig = 0x02014b50L
        const val LocalHeaderSig = 0x04034b50L
        const val Zip64Marker = 0xFFFF_FFFFL
    }
}
