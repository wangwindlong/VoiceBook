package us.wangxy.voicebook.reader.listen

import us.wangxy.voicebook.reader.epub.Block

/**
 * One unit of speech. [start]/[end] are chapter-relative char offsets in the same space as
 * [Block.startOffset], so a sentence maps straight onto the paginator's page anchors.
 */
data class ListenSentence(val start: Int, val end: Int, val text: String)

/**
 * Splits chapter blocks into sentences for synthesis. The TTS backend handles one sentence per
 * call, and sentence granularity is what the reader highlights and resumes from.
 */
object ListenScript {
    /** Long unpunctuated runs (hard-wrapped TXT, lists) are cut near this length. */
    const val MaxSentenceChars = 120

    /** A soft break (comma, colon, space) closer than this to the sentence start is not used. */
    private const val MinSoftBreakChars = 24

    private const val Terminators = "。！？!?；;…"
    private const val Closers = "”’」』）)]】》\"'"
    private const val SoftBreaks = "，,、：: "
    private val Abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "no")

    fun sentences(blocks: List<Block>): List<ListenSentence> {
        val out = ArrayList<ListenSentence>()
        for (block in blocks) {
            if (block !is Block.Paragraph) continue
            val text = block.spans.joinToString("") { it.text }
            // A heading is read as one unit even when it contains "？" or "！".
            split(text, block.startOffset, out, cutAtTerminators = block.headingLevel == 0)
        }
        return out
    }

    /** Index of the sentence that contains [offset], or the first one after it; -1 past the end. */
    fun indexAt(sentences: List<ListenSentence>, offset: Int): Int = sentences.indexOfFirst { it.end > offset }

    private fun split(text: String, base: Int, out: MutableList<ListenSentence>, cutAtTerminators: Boolean) {
        var start = 0
        var lastSoft = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                emit(text, base, start, i, out)
                start = i + 1
                lastSoft = -1
            } else if (cutAtTerminators && isTerminator(text, i)) {
                var end = i + 1
                while (end < text.length && (text[end] in Terminators || text[end] in Closers)) end++
                emit(text, base, start, end, out)
                start = end
                lastSoft = -1
                i = end
                continue
            } else if (c in SoftBreaks && i - start >= MinSoftBreakChars) {
                lastSoft = i
            }
            if (i + 1 - start >= MaxSentenceChars) {
                val cut = if (lastSoft > start) lastSoft + 1 else i + 1
                emit(text, base, start, cut, out)
                start = cut
                lastSoft = -1
            }
            i++
        }
        emit(text, base, start, text.length, out)
    }

    private fun isTerminator(text: String, i: Int): Boolean {
        val c = text[i]
        if (c in Terminators) return true
        if (c != '.') return false
        val next = text.getOrNull(i + 1)
        // "3.14", "example.com", "..." inside a word are not sentence ends.
        if (next != null && !next.isWhitespace() && next !in Closers) return false
        var w = i
        while (w > 0 && text[w - 1].isLetter()) w--
        return text.substring(w, i).lowercase() !in Abbreviations
    }

    private fun emit(text: String, base: Int, from: Int, to: Int, out: MutableList<ListenSentence>) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return
        val piece = text.substring(s, e)
        if (piece.none { it.isLetterOrDigit() }) return
        out += ListenSentence(base + s, base + e, piece)
    }
}
