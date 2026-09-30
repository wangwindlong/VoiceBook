package us.wangxy.voicebook.reader.api

import java.security.MessageDigest

actual fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
