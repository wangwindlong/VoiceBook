package us.wangxy.voicebook

import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.txt.TxtBook
import us.wangxy.voicebook.reader.txt.TxtFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TxtBookTest {

    @Test
    fun splitsOnChapterHeadings() {
        val text = """
            开篇的引言内容。
            第一章 起点
            起点的正文。
            第二章 发展
            发展的正文。
            第三章 结局
            结局的正文。
        """.trimIndent()
        val book = TxtBook.parse(text.encodeToByteArray())
        assertEquals(4, book.chapters.size) // 开篇 + 3 章
        assertEquals("开篇", book.chapters[0].title)
        assertEquals("第一章 起点", book.chapters[1].title)
        val heading = book.chapterBlocks(1).filterIsInstance<Block.Paragraph>().first { it.headingLevel == 1 }
        assertEquals("第一章 起点", heading.spans.single().text)
    }

    @Test
    fun decodesGbkBytes() {
        val text = "第一章 测试\n中文内容，含标点与 English。\n第二章 续\n更多内容。\n"
        val book = TxtBook.parse(text.toByteArray(charset("GBK")))
        assertEquals("第一章 测试", book.chapters[0].title)
        assertEquals("第二章 续", book.chapters[1].title)
    }

    @Test
    fun decodesUtf8WithBom() {
        val text = "第一章 A\n内容一\n第二章 B\n内容二\n第三章 C\n内容三\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray()
        val book = TxtBook.parse(bytes)
        assertEquals("第一章 A", book.chapters[0].title)
        assertEquals(3, book.chapters.size)
    }

    @Test
    fun chunksWithoutHeadings() {
        val text = (1..400).joinToString("\n") { "第 $it 行的正文内容，中文小说常见的一行一个段落，用来凑长度。" }
        val book = TxtBook.parse(text.encodeToByteArray())
        assertTrue(book.chapters.size > 1, "无标题的整本书应按体积分成多节")
    }

    @Test
    fun rejectsZipAndGarbage() {
        val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)
        assertFailsWith<TxtFormatException> { TxtBook.parse(zipMagic) }
        val binary = ByteArray(2000) { (it % 256).toByte() } // invalid UTF-8, control-char heavy
        assertFailsWith<TxtFormatException> { TxtBook.parse(binary) }
    }
}
