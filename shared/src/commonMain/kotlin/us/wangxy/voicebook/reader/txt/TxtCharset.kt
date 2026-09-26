package us.wangxy.voicebook.reader.txt

import kotlin.text.CharacterCodingException

/**
 * Decodes TXT bytes: strict UTF-8 first, then the platform's legacy fallbacks
 * (GBK/GB18030 for Chinese files). Null when nothing decodes cleanly.
 */
internal fun decodeTextBytes(bytes: ByteArray): String? =
    try {
        bytes.decodeToString(0, bytes.size, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        decodeLegacyTextBytes(bytes)
    }

internal expect fun decodeLegacyTextBytes(bytes: ByteArray): String?
