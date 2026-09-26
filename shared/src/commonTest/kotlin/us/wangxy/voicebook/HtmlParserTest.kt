package us.wangxy.voicebook

import us.wangxy.voicebook.reader.epub.HtmlParser
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlParserTest {

    @Test
    fun parsesParagraphsWithBoldItalic() {
        val blocks = HtmlParser.parse(
            "<html><head><title>x</title><style>p{}</style></head>" +
                "<body><p>第一段 <b>加粗</b> 结束</p><p>第二段 <i>斜体</i></p></body></html>",
        )
        assertEquals(2, blocks.size)
        val p1 = blocks[0] as Block.Paragraph
        assertEquals(3, p1.spans.size)
        assertEquals("第一段 ", p1.spans[0].text)
        assertTrue(p1.spans[1].bold)
        assertEquals("加粗", p1.spans[1].text)
        val p2 = blocks[1] as Block.Paragraph
        assertTrue(p2.spans[1].italic)
    }

    @Test
    fun headingsGetLevels() {
        val blocks = HtmlParser.parse("<h1>标题一</h1><h3>标题三</h3><p>正文</p>")
        assertEquals(1, (blocks[0] as Block.Paragraph).headingLevel)
        assertEquals(3, (blocks[1] as Block.Paragraph).headingLevel)
        assertEquals(0, (blocks[2] as Block.Paragraph).headingLevel)
    }

    @Test
    fun imagesAndRulers() {
        val blocks = HtmlParser.parse("<p>前</p><hr/><img src=\"../img/pic.jpg\" alt=\"图\"/><p>后</p>")
        assertTrue(blocks[1] is Block.Ruler)
        val img = blocks[2] as Block.Image
        assertEquals("../img/pic.jpg", img.src)
    }

    @Test
    fun unclosedTagsTolerated() {
        val blocks = HtmlParser.parse("<ul><li>一<li>二<ul><li>三</ul></ul><p>未闭合段落")
        val text = blocks.filterIsInstance<Block.Paragraph>().joinToString("") {
            it.spans.joinToString("") { s -> s.text }
        }
        assertTrue(text.contains("一"))
        assertTrue(text.contains("二"))
        assertTrue(text.contains("三"))
        assertTrue(text.contains("未闭合段落"))
        // All three items live in (nested) ul lists → bullet markers.
        assertEquals("•", (blocks[0] as Block.Paragraph).bullet)
        assertEquals("•", (blocks[1] as Block.Paragraph).bullet)
        assertEquals("•", (blocks[2] as Block.Paragraph).bullet)
    }

    @Test
    fun scriptAndStyleContentDropped() {
        val blocks = HtmlParser.parse("<script>var a = 'b';</script><style>.x{}</style><p>正文</p>")
        assertEquals(1, blocks.size)
        assertEquals("正文", (blocks[0] as Block.Paragraph).spans.single().text)
    }

    @Test
    fun entitiesDecodedAndWhitespaceCollapsed() {
        val blocks = HtmlParser.parse("<p>Tom &amp;   Jerry&#8212;行了</p>")
        assertEquals("Tom & Jerry—行了", (blocks[0] as Block.Paragraph).spans.single().text)
    }

    @Test
    fun offsetsCoverChapterTextContiguously() {
        val blocks = HtmlParser.parse("<h1>T</h1><p>aaaa</p><p>bb</p>")
        assertEquals(0, blocks[0].startOffset)
        assertEquals(1, blocks[0].endOffset)
        assertEquals(1, blocks[1].startOffset)
        assertEquals(5, blocks[1].endOffset)
        assertEquals(5, blocks[2].startOffset)
        assertEquals(7, blocks[2].endOffset)
    }
}

class ImageSizeTest {

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArray(24)
        out[0] = 0x89.toByte(); out[1] = 'P'.code.toByte(); out[2] = 'N'.code.toByte(); out[3] = 'G'.code.toByte()
        fun put(at: Int, v: Int) {
            out[at] = ((v ushr 24) and 0xFF).toByte()
            out[at + 1] = ((v ushr 16) and 0xFF).toByte()
            out[at + 2] = ((v ushr 8) and 0xFF).toByte()
            out[at + 3] = (v and 0xFF).toByte()
        }
        put(16, width)
        put(20, height)
        return out
    }

    @Test
    fun pngDimensions() {
        assertEquals(800 to 600, ImageSize.of(png(800, 600)))
    }

    @Test
    fun gifDimensions() {
        val bytes = "GIF89a".encodeToByteArray() + byteArrayOf(0x20.toByte(), 0x00, 0x1E.toByte(), 0x00) + ByteArray(16)
        assertEquals(32 to 30, ImageSize.of(bytes))
    }

    @Test
    fun jpegSof0() {
        // SOF0 marker segment: FFC0, len=0x0011, precision=8, height=0x012C, width=0x01F4
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // SOI
            .plus(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x01, 0x2C, 0x01, 0xF4.toByte()))
            .plus(ByteArray(15))
        assertEquals(500 to 300, ImageSize.of(bytes))
    }

    @Test
    fun svgIsNull() {
        kotlin.test.assertNull(ImageSize.of("<svg xmlns=\"...\"/>".encodeToByteArray()))
    }
}
