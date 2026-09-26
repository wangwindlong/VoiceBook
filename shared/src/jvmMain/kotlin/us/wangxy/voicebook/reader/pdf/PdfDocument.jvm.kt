package us.wangxy.voicebook.reader.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.min

internal actual fun openPdfDocument(bytes: ByteArray): PdfDocument? = runCatching {
    JvmPdfDocument(Loader.loadPDF(bytes))
}.getOrNull()

private class JvmPdfDocument(private val document: PDDocument) : PdfDocument {
    private val renderer = PDFRenderer(document)
    // PDFRenderer is not thread-safe for concurrent renderImage calls.
    private val lock = Any()

    override val pageCount: Int get() = document.numberOfPages

    override fun pageSize(index: Int): PdfPageSize {
        val box = document.getPage(index).mediaBox
        return PdfPageSize(box.width, box.height)
    }

    override fun renderPage(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = runCatching {
        val size = pageSize(index)
        val scale = min(
            maxWidthPx.toFloat() / size.widthPt,
            maxHeightPx.toFloat() / size.heightPt,
        ).coerceAtMost(MaxRenderScale)
        val image = synchronized(lock) { renderer.renderImage(index, scale) }
        val png = ByteArrayOutputStream().also { out -> ImageIO.write(image, "png", out) }.toByteArray()
        Image.makeFromEncoded(png).toComposeImageBitmap()
    }.getOrNull()

    override fun close() {
        runCatching { document.close() }
    }

    private companion object {
        private const val MaxRenderScale = 3f
    }
}
