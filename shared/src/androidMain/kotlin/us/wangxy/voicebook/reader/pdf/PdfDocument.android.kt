package us.wangxy.voicebook.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.math.min

internal actual fun openPdfDocument(bytes: ByteArray): PdfDocument? = runCatching {
    AndroidPdfDocument.open(bytes)
}.getOrNull()

private class AndroidPdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val tempFile: File,
) : PdfDocument {
    // PdfRenderer is not thread-safe for concurrent openPage/render.
    private val lock = Any()

    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(index: Int): PdfPageSize = synchronized(lock) {
        renderer.openPage(index).use { page ->
            PdfPageSize(page.width.toFloat(), page.height.toFloat())
        }
    }

    override fun renderPage(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = runCatching {
        synchronized(lock) {
            renderer.openPage(index).use { page ->
                val scale = min(
                    maxWidthPx.toFloat() / page.width,
                    maxHeightPx.toFloat() / page.height,
                ).coerceAtMost(MaxRenderScale)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                // PdfRenderer leaves the background transparent; paint white first.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }.getOrNull()

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            tempFile.delete()
        }
    }

    companion object {
        private const val MaxRenderScale = 3f

        fun open(bytes: ByteArray): AndroidPdfDocument {
            // PdfRenderer needs a seekable file descriptor, not an in-memory stream.
            val context = KoinPlatform.getKoin().get<Context>()
            val file = File.createTempFile("voicebook-", ".pdf", context.cacheDir)
            file.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                AndroidPdfDocument(PdfRenderer(pfd), file)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
    }
}
