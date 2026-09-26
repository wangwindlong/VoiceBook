package us.wangxy.voicebook.reader.txt

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal actual fun decodeLegacyTextBytes(bytes: ByteArray): String? =
    listOf("GBK", "GB18030").firstNotNullOfOrNull { name ->
        runCatching {
            java.nio.charset.Charset.forName(name).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
