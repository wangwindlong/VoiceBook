package us.wangxy.voicebook.reader.txt

// No legacy Chinese charset decoder wired up on web yet (UTF-8 TXT works via the
// common path); non-UTF-8 files surface the clear "无法识别编码" message.
internal actual fun decodeLegacyTextBytes(bytes: ByteArray): String? = null
