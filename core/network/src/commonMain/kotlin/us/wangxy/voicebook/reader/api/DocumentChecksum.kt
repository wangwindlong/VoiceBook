package us.wangxy.voicebook.reader.api

import okio.ByteString.Companion.toByteString

/** Platform MD5 used only as the KOReader/CWA document identifier. */
fun md5Hex(bytes: ByteArray): String = bytes.toByteString().md5().hex()

/** Same partial sampling algorithm used by CWA's KOReader checksum backfill. */
fun koreaderPartialMd5(bytes: ByteArray): String {
    val sampled = ArrayList<Byte>(minOf(bytes.size, 12 * 1024))
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + 1024, bytes.size)
        for (i in offset until end) sampled += bytes[i]
        if (offset == 0) offset = 1024 else {
            if (offset > Int.MAX_VALUE / 4) break
            offset *= 4
        }
    }
    return md5Hex(sampled.toByteArray())
}
