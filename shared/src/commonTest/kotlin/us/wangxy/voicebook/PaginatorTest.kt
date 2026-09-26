package us.wangxy.voicebook

import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.paginate.LineBox
import us.wangxy.voicebook.reader.paginate.LineMeasurer
import us.wangxy.voicebook.reader.paginate.PageLayout
import us.wangxy.voicebook.reader.paginate.Paginator
import us.wangxy.voicebook.reader.render.ReaderStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic fake: fixed chars-per-line, line height tracks the style so font-size
 *  changes actually re-flow, keeping the pagination math exact. */
private class FakeMeasurer(
    private val charsPerLine: Int = 10,
) : LineMeasurer {
    override fun lines(paragraph: Block.Paragraph, style: ReaderStyle, maxWidthPx: Float, fontScale: Float): List<LineBox> {
        val text = paragraph.spans.joinToString("") { it.text }
        if (text.isEmpty()) return emptyList()
        val count = (text.length + charsPerLine - 1) / charsPerLine
        return List(count) { line ->
            LineBox(heightPx = style.lineHeightPx * fontScale, startChar = line * charsPerLine)
        }
    }
}

class PaginatorTest {

    private val style = ReaderStyle(
        fontSizeSp = 18f,
        horizontalPaddingPx = 24f,
        topPaddingPx = 20f,
        bottomPaddingPx = 28f,
    )
    private val layout = PageLayout(contentWidthPx = 400f, contentHeightPx = 200f)
    private val measurer = FakeMeasurer(charsPerLine = 10)

    private fun paragraphs(vararg texts: String): List<Block> =
        texts.mapIndexed { i, t ->
            val start = if (i == 0) 0 else texts.take(i).sumOf { it.length }
            Block.Paragraph(start, start + t.length, listOf(us.wangxy.voicebook.reader.epub.Span(t)))
        }

    private fun heading(text: String, at: Int): Block =
        Block.Paragraph(at, at + text.length, listOf(us.wangxy.voicebook.reader.epub.Span(text)), headingLevel = 2)

    @Test
    fun fillsPagesAndBreaksAcrossPages() {
        // lineHeight 29.7px → 6 lines/page; 110 chars = 11 lines → 6 on page 1, 5 on page 2.
        val pages = Paginator.paginate(paragraphs("a".repeat(110)), measurer, style, layout)
        assertEquals(2, pages.size)
        val first = pages[0].entries.single() as us.wangxy.voicebook.reader.paginate.PageEntry.Text
        assertEquals(0, first.fromLine)
        assertEquals(6, first.toLine)
        val second = pages[1].entries.single() as us.wangxy.voicebook.reader.paginate.PageEntry.Text
        assertEquals(6, second.fromLine)
        assertEquals(11, second.toLine)
    }

    @Test
    fun headingNeverDanglesAtPageBottom() {
        // 5 body lines (148px) then a heading needs 29.7 + 40px: doesn't fit in the
        // remaining ~51px → heading moves to page 2 with a body line after it.
        val blocks = listOf(
            Block.Paragraph(0, 50, listOf(us.wangxy.voicebook.reader.epub.Span("x".repeat(50)))),
            heading("标题", 50),
            Block.Paragraph(52, 82, listOf(us.wangxy.voicebook.reader.epub.Span("y".repeat(30)))),
        )
        val pages = Paginator.paginate(blocks, measurer, style, layout)
        assertTrue(pages.size >= 2)
        val headingPage = pages.first { page ->
            page.entries.filterIsInstance<us.wangxy.voicebook.reader.paginate.PageEntry.Text>()
                .any { it.paragraph.headingLevel > 0 }
        }
        val headingEntry = headingPage.entries.filterIsInstance<us.wangxy.voicebook.reader.paginate.PageEntry.Text>()
            .first { it.paragraph.headingLevel > 0 }
        // Heading is not the last thing on its page (a body line follows), except on the final page.
        assertTrue(headingPage.entries.last() !== headingEntry || pages.last() == headingPage)
    }

    @Test
    fun anchorsMapOffsetsToPages() {
        val blocks = paragraphs("a".repeat(110), "b".repeat(110))
        val pages = Paginator.paginate(blocks, measurer, style, layout)
        assertTrue(pages.size >= 2)
        assertEquals(0, Paginator.pageForOffset(pages, 0))
        assertEquals(0, Paginator.pageForOffset(pages, 30))
        // Offsets beyond the last page's anchor land on the last page.
        val lastPage = pages.last()
        assertEquals(
            pages.size - 1,
            Paginator.pageForOffset(pages, lastPage.anchorOffset + 5),
        )
    }

    @Test
    fun imageBlockReservesBoxAndRespectsRatio() {
        val image = Block.Image(0, "img/pic.png")
        val pages = Paginator.paginate(listOf(image), measurer, style, layout, imageAspectRatios = mapOf("img/pic.png" to 2f))
        val pic = pages.single().entries.single() as us.wangxy.voicebook.reader.paginate.PageEntry.Picture
        // height = min(width/ratio, max) = 200; box width = height * ratio = 400.
        assertEquals(200f, pic.heightPx, 0.01f)
        assertEquals(400f, pic.widthPx, 0.01f)
    }

    @Test
    fun fontSizeChangeKeepsAnchorStable() {
        val blocks = paragraphs("ab".repeat(200))
        val small = Paginator.paginate(blocks, measurer, style, layout)
        val big = Paginator.paginate(blocks, measurer, style.copy(fontSizeSp = 26f), layout)
        assertTrue(small.size < big.size, "larger font must produce more pages: ${small.size} vs ${big.size}")
        val anchor = 120
        val smallPage = small[Paginator.pageForOffset(small, anchor)].anchorOffset
        val bigPage = big[Paginator.pageForOffset(big, anchor)].anchorOffset
        // Both styles land the anchor on a page whose first offset is ≤ anchor.
        assertTrue(smallPage <= anchor && bigPage <= anchor)
    }
}
