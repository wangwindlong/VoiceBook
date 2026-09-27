@file:OptIn(ExperimentalForeignApi::class)

package us.wangxy.voicebook.reader.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.math.min

internal actual fun openPdfDocument(bytes: ByteArray): PdfDocument? = runCatching {
    PDFDocument(data = bytes.toNSData())?.let(::IosPdfDocument)
}.getOrNull()

private class IosPdfDocument(private val document: PDFDocument) : PdfDocument {
    override val pageCount: Int get() = document.pageCount.toInt()

    override fun pageSize(index: Int): PdfPageSize {
        val page = document.pageAtIndex(index.toULong()) ?: return PdfPageSize(612f, 792f)
        return page.boundsForBox(kPDFDisplayBoxMediaBox).useContents {
            PdfPageSize(size.width.toFloat(), size.height.toFloat())
        }
    }

    override fun renderPage(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = runCatching {
        val page = document.pageAtIndex(index.toULong()) ?: return null
        val (widthPt, heightPt) = page.boundsForBox(kPDFDisplayBoxMediaBox).useContents {
            size.width to size.height
        }
        val scale = min(maxWidthPx / widthPt, maxHeightPx / heightPt).coerceAtMost(3.0)
        val image = page.thumbnailOfSize(
            size = CGSizeMake(widthPt * scale, heightPt * scale),
            forBox = kPDFDisplayBoxMediaBox,
        ) ?: return null
        val data = UIImagePNGRepresentation(image) ?: return null
        Image.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
    }.getOrNull()
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
}
