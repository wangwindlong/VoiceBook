package us.wangxy.voicebook.data

actual fun fileNameFor(key: String): String =
    key.map { c -> if (c.isLetterOrDigit() || c == '.') c else '_' }.joinToString("") + ".bin"
