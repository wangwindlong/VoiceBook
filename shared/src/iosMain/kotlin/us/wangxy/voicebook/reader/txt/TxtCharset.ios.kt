@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.wangxy.voicebook.reader.txt

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.posix.memcpy

internal actual fun decodeLegacyTextBytes(bytes: ByteArray): String? {
    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
    return memScoped {
        val data: NSData = bytes.toNSData()
        NSString.create(data = data, encoding = encoding)?.toString()
    }
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
}
