package us.wangxy.voicebook.voice.model.archive

import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readTo

/**
 * Extracts regular files and directories from a tar stream (ustar, GNU long names, PAX paths).
 * The first [stripComponents] path segments are dropped, like `tar --strip-components`.
 */
internal class TarExtractor(
    private val fs: FileSystem,
    private val stripComponents: Int = 1,
) {
    fun extract(source: Source, destination: Path) {
        fs.createDirectories(destination)
        val header = ByteArray(BlockSize)
        var longName: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        while (true) {
            if (!source.request(BlockSize.toLong())) {
                if (source.exhausted()) return
                throw IOException("Truncated tar header")
            }
            source.readTo(header, 0, BlockSize)
            if (header.all { it.toInt() == 0 }) return

            val type = header[156].toInt().toChar()
            val size = paxSize ?: parseNumber(header, 124, 12)
            val name = longName ?: paxPath ?: headerName(header)
            longName = null
            paxPath = null
            paxSize = null

            when (type) {
                'L' -> longName = readBytes(source, size).decodeToString().trimEnd('\u0000')
                'x' -> {
                    val records = parsePax(readBytes(source, size))
                    paxPath = records["path"]
                    paxSize = records["size"]?.toLongOrNull()
                }
                '0', '\u0000', '7' -> {
                    val target = targetPath(destination, name)
                    if (target == null) {
                        source.skip(size)
                    } else {
                        target.parent?.let { fs.createDirectories(it) }
                        fs.sink(target).buffered().use { sink -> source.readTo(sink, size) }
                    }
                }
                '5' -> targetPath(destination, name)?.let { fs.createDirectories(it) }
                else -> source.skip(size) // links, devices, global PAX headers
            }
            if (type != '5') source.skip(padding(size))
        }
    }

    private fun targetPath(destination: Path, name: String): Path? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Unsafe tar entry: $name")
        val kept = parts.drop(stripComponents)
        if (kept.isEmpty() || kept.last().startsWith("._") || kept.first() == "__MACOSX") return null
        return Path(destination, *kept.toTypedArray())
    }

    private fun headerName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val isUstar = cString(header, 257, 6).startsWith("ustar")
        val prefix = if (isUstar) cString(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun readBytes(source: Source, size: Long): ByteArray {
        if (size !in 0..MaxMetadataSize) throw IOException("Tar metadata entry too large: $size")
        val bytes = ByteArray(size.toInt())
        source.readTo(bytes, 0, bytes.size)
        return bytes
    }

    private companion object {
        const val BlockSize = 512
        const val MaxMetadataSize = 1L shl 20

        fun padding(size: Long): Long = (BlockSize - size % BlockSize) % BlockSize

        fun cString(bytes: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && bytes[end].toInt() != 0) end++
            return bytes.decodeToString(offset, end)
        }

        /** Octal ASCII, or GNU base-256 when the high bit of the first byte is set. */
        fun parseNumber(bytes: ByteArray, offset: Int, length: Int): Long {
            if (bytes[offset].toInt() and 0x80 != 0) {
                var value = (bytes[offset].toLong() and 0x7F)
                for (i in offset + 1 until offset + length) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
                return value
            }
            val text = cString(bytes, offset, length).trim()
            return if (text.isEmpty()) 0 else text.toLong(8)
        }

        /** PAX records are `"<length> <key>=<value>\n"`, with the length counted in bytes. */
        fun parsePax(bytes: ByteArray): Map<String, String> {
            val records = mutableMapOf<String, String>()
            var pos = 0
            while (pos < bytes.size) {
                var space = pos
                while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
                val length = bytes.decodeToString(pos, space).toIntOrNull() ?: break
                val end = pos + length
                if (length <= 0 || end > bytes.size) break
                val record = bytes.decodeToString(space + 1, end - 1)
                val eq = record.indexOf('=')
                if (eq > 0) records[record.substring(0, eq)] = record.substring(eq + 1)
                pos = end
            }
            return records
        }
    }
}
