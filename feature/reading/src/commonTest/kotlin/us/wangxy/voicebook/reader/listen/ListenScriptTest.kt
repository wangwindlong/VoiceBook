package us.wangxy.voicebook.reader.listen

import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListenScriptTest {

    private fun paragraphs(vararg texts: String, headingAt: Int = -1): List<Block> {
        var offset = 0
        return texts.mapIndexed { i, t ->
            val p = Block.Paragraph(offset, offset + t.length, listOf(Span(t)), headingLevel = if (i == headingAt) 2 else 0)
            offset += t.length
            p
        }
    }

    private fun texts(vararg paragraphs: String) = ListenScript.sentences(paragraphs(*paragraphs)).map { it.text }

    @Test
    fun splitsChineseSentencesAndKeepsClosingQuotes() {
        assertEquals(
            listOf("他说：“你好。”", "我点点头！", "真的吗？！", "嗯……", "走吧。"),
            texts("他说：“你好。”我点点头！真的吗？！嗯……走吧。"),
        )
    }

    @Test
    fun offsetsPointBackIntoTheChapterText() {
        val first = "第一句。第二句。"
        val second = "  第三句  "
        val blocks = paragraphs(first, second)
        val chapter = first + second
        val sentences = ListenScript.sentences(blocks)
        assertEquals(3, sentences.size)
        for (s in sentences) assertEquals(s.text, chapter.substring(s.start, s.end))
        assertEquals(first.length + 2, sentences[2].start)
    }

    @Test
    fun englishDotsInsideNumbersAndAbbreviationsDoNotSplit() {
        assertEquals(
            listOf("Mr. Smith paid 3.14 dollars at example.com today.", "Then he left."),
            texts("Mr. Smith paid 3.14 dollars at example.com today. Then he left."),
        )
    }

    @Test
    fun headingsAreReadWhole() {
        val sentences = ListenScript.sentences(paragraphs("第一章 谁？是谁！", "正文。", headingAt = 0))
        assertEquals(listOf("第一章 谁？是谁！", "正文。"), sentences.map { it.text })
    }

    @Test
    fun longRunsWithoutTerminatorsAreCutAtASoftBreak() {
        val clause = "这是一段没有句号的很长很长的文字内容用来测试切分，"
        val text = clause.repeat(8)
        val sentences = ListenScript.sentences(paragraphs(text))
        assertTrue(sentences.size > 1)
        assertTrue(sentences.all { it.text.length <= ListenScript.MaxSentenceChars })
        assertTrue(sentences.dropLast(1).all { it.text.endsWith("，") }, "cuts land after a comma")
        assertEquals(text, sentences.joinToString("") { it.text })
    }

    @Test
    fun punctuationOnlyPiecesAndImagesAreSkipped() {
        val blocks = listOf(
            Block.Paragraph(0, 3, listOf(Span("……。"))),
            Block.Image(3, "a.png"),
            Block.Paragraph(3, 6, listOf(Span("好的。"))),
        )
        assertEquals(listOf("好的。"), ListenScript.sentences(blocks).map { it.text })
    }

    @Test
    fun indexAtFindsTheSentenceContainingAnOffset() {
        val sentences = ListenScript.sentences(paragraphs("一二三。四五六。"))
        assertEquals(0, ListenScript.indexAt(sentences, 0))
        assertEquals(1, ListenScript.indexAt(sentences, 5))
        assertEquals(-1, ListenScript.indexAt(sentences, 8))
    }
}
