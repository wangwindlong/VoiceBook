package us.wangxy.voicebook.reader.epub

/**
 * Image dimension sniffing from header bytes only — pagination needs an image's aspect
 * ratio before deciding which page it lands on, without decoding the full bitmap.
 * Returns null for anything else (SVG and exotic formats get a placeholder box).
 */
internal object ImageSize {

    fun of(bytes: ByteArray): Pair<Int, Int>? = when {
        bytes.size >= 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() ->
            be32(bytes, 16) to be32(bytes, 20) // PNG IHDR width/height

        bytes.size >= 10 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() ->
            le16(bytes, 6) to le16(bytes, 8) // GIF logical screen size

        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
            jpegSize(bytes)

        else -> null
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD9) {
                i += 2 // standalone markers (TEM/SOI/EOI/RSTn)
                continue
            }
            val length = be16(bytes, i + 2)
            // SOF0..SOF15 except DHT(0xC4), JPG(0xC8) and DAC(0xCC) carry the frame size.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = be16(bytes, i + 5)
                val width = be16(bytes, i + 7)
                if (height > 0 && width > 0) return width to height
            }
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun be32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun be16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}
