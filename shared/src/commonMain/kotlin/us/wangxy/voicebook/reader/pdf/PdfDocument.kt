package us.wangxy.voicebook.reader.pdf

import androidx.compose.ui.graphics.ImageBitmap

/** Page dimensions in PDF points (1/72 inch). */
data class PdfPageSize(val widthPt: Float, val heightPt: Float)

/**
 * One opened PDF document, rendered to bitmaps by the per-platform engine
 * (Android PdfRenderer / desktop PDFBox / iOS PDFKit). Not thread-safe for
 * concurrent renders — the reader serializes them on a background dispatcher.
 */
interface PdfDocument {
    val pageCount: Int

    fun pageSize(index: Int): PdfPageSize

    /**
     * Renders page [index] to fit inside [maxWidthPx] × [maxHeightPx], preserving
     * aspect. Blocking — call from a background dispatcher.
     */
    fun renderPage(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap?

    /** Releases native resources; safe to call more than once. */
    fun close() {}
}

/** Opens a PDF; null when the bytes are not a renderable PDF. */
internal expect fun openPdfDocument(bytes: ByteArray): PdfDocument?
