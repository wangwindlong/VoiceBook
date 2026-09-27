package us.wangxy.voicebook.reader.txt

import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.epub.Span

/**
 * Plain-text book. Chapters are split on "第…章/卷/回"-style heading lines when the
 * file contains enough of them, else on ~6k-character chunks, so chapter-based
 * progress and the TOC stay meaningful. Each non-blank source line is one paragraph
 * (the common hard-wrapped Chinese TXT layout), heading lines become heading blocks.
 */
class TxtBook private constructor(override val chapters: List<EpubBook.Chapter>) : ReadableBook {

    override fun chapterBlocks(index: Int): List<Block> =
        chapters.getOrNull(index)?.blocks ?: emptyList()

    private class Line(val text: String, val heading: Boolean)

    companion object {
        /** "第一章 …" / "Chapter 12 …" style chapter headings. */
        private val Heading = Regex(
            """^\s*(第\s*[0-9零一二三四五六七八九十百千万两]+\s*[章卷回节部集篇].{0,40}""" +
                """|序章.{0,20}|楔子|尾声.{0,10}|番外.{0,20}|Chapter\s+\d+.{0,40})\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /** Characters per fallback chunk when the file has no recognizable headings. */
        private const val ChunkChars = 6000

        /** Ratio of control characters above which decoded text is judged not plain prose. */
        private const val MaxControlRatio = 0.02

        fun parse(bytes: ByteArray): TxtBook {
            if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                throw TxtFormatException("这不是纯文本文件（是 zip 容器，请以 EPUB 打开）")
            }
            val text = decodeTextBytes(bytes)
                ?: throw TxtFormatException("无法识别 TXT 文件编码（请转存为 UTF-8 或 GBK/GB18030）")
            val lines = text.removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { Line(it, Heading.matches(it)) } }
            if (lines.isEmpty()) throw TxtFormatException("TXT 文件是空的")

            val controlRatio = text.count { it < ' ' && it != '\n' }.toDouble() / text.length.coerceAtLeast(1)
            if (controlRatio > MaxControlRatio) {
                throw TxtFormatException("文件不是纯文本内容，无法阅读")
            }

            val chapters = if (lines.count { it.heading } >= 2) splitByHeadings(lines) else splitBySize(lines)
            return TxtBook(chapters.map(::toChapter))
        }

        private fun splitByHeadings(lines: List<Line>): List<Pair<String, List<Line>>> {
            val groups = ArrayList<Pair<String, MutableList<Line>>>()
            for (line in lines) {
                if (line.heading || groups.isEmpty()) {
                    groups += (if (line.heading) line.text else "开篇") to mutableListOf()
                }
                groups.last().second += line
            }
            return groups
        }

        private fun splitBySize(lines: List<Line>): List<Pair<String, List<Line>>> {
            val groups = ArrayList<Pair<String, List<Line>>>()
            var current = ArrayList<Line>()
            var chars = 0
            for (line in lines) {
                current += line
                chars += line.text.length
                if (chars >= ChunkChars) {
                    groups += "第 ${groups.size + 1} 节" to current
                    current = ArrayList()
                    chars = 0
                }
            }
            if (current.isNotEmpty()) groups += "第 ${groups.size + 1} 节" to current
            return groups
        }

        /** Offsets are within-chapter character counts, same anchor semantics as EPUB. */
        private fun toChapter(group: Pair<String, List<Line>>): EpubBook.Chapter {
            val (title, lines) = group
            val chapter = EpubBook.Chapter(title, null)
            var offset = 0
            chapter.blocks = lines.map { line ->
                val start = offset
                offset += line.text.length
                Block.Paragraph(
                    start, offset,
                    listOf(Span(line.text)),
                    headingLevel = if (line.heading) 1 else 0,
                )
            }
            return chapter
        }
    }
}

class TxtFormatException(message: String) : Exception(message)
