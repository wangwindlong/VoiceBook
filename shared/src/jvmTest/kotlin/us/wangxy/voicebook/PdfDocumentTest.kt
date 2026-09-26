package us.wangxy.voicebook

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import us.wangxy.voicebook.reader.pdf.openPdfDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renders a real PDF through the desktop engine (PDFBox). */
class PdfDocumentTest {

    /** Two-page PDF with one text line per page. */
    private fun buildPdf(): ByteArray = PDDocument().use { doc ->
        repeat(2) { i ->
            val page = PDPage(PDRectangle.A6)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(20f, page.mediaBox.height - 30f)
                cs.showText("page ${i + 1}")
                cs.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun opensAndCountsPages() {
        val pdf = openPdfDocument(buildPdf())
        assertNotNull(pdf)
        assertEquals(2, pdf!!.pageCount)
        pdf.close()
    }

    @Test
    fun rendersPageToFitViewport() {
        val pdf = openPdfDocument(buildPdf())
        assertNotNull(pdf)
        val bitmap = pdf!!.renderPage(1, maxWidthPx = 400, maxHeightPx = 600)
        assertNotNull(bitmap)
        // A6 is taller than wide (4:3 landscape false): fit limited by width here.
        assertTrue(bitmap!!.width <= 400 && bitmap.height <= 600)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        pdf.close()
    }

    @Test
    fun rejectsNonPdfBytes() {
        assertNull(openPdfDocument("not a pdf".encodeToByteArray()))
    }
}
