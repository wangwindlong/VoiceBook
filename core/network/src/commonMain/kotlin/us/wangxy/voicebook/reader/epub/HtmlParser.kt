package us.wangxy.voicebook.reader.epub

/**
 * Layout-neutral content model for one chapter. Pure data (no Compose types) so the
 * parser is unit-testable anywhere; [us.wangxy.voicebook.reader.render.BlockRenderer]
 * turns spans into AnnotatedStrings at draw time. [startOffset]/[endOffset] count
 * characters within the chapter (only Paragraph text counts), giving the reader a
 * durable position anchor that survives re-pagination when the font size changes.
 */
sealed interface Block {
    val startOffset: Int
    val endOffset: Int

    data class Paragraph(
        override val startOffset: Int,
        override val endOffset: Int,
        val spans: List<Span>,
        val headingLevel: Int = 0,
        val indent: Boolean = false,
        val quote: Boolean = false,
        val bullet: String? = null,
        val preformatted: Boolean = false,
    ) : Block

    data class Image(override val startOffset: Int, val src: String) : Block {
        override val endOffset: Int get() = startOffset
    }

    data class Ruler(override val startOffset: Int) : Block {
        override val endOffset: Int get() = startOffset
    }
}

data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

/**
 * Lenient XHTML/HTML subset parser for EPUB chapters: handles real-world books that
 * aren't well-formed XML (unclosed <p>/<li>, void tags, stray entities). Produces the
 * block list above; unknown tags are ignored, <head>/<script>/<style> content is
 * dropped, whitespace collapses per HTML rules (preserved inside <pre>).
 */
object HtmlParser {

    fun parse(html: String): List<Block> {
        val out = ArrayList<Block>()
        val builder = Builder(out)
        var i = 0
        val n = html.length

        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                builder.text(html.substring(i))
                break
            }
            if (lt > i) builder.text(html.substring(i, lt))
            val gt = html.indexOf('>', lt + 1)
            if (gt < 0) break
            when (html.getOrNull(lt + 1)) {
                '!' -> if (html.startsWith("<!--", lt)) {
                    val close = html.indexOf("-->", lt + 4)
                    i = if (close < 0) n else close + 3
                    continue
                }

                '?' -> i = gt + 1

                '/' -> {
                    val name = html.substring(lt + 2, gt).trim().lowercase()
                    builder.close(name)
                }

                else -> {
                    val body = html.substring(lt + 1, gt)
                    val selfClosing = body.endsWith("/")
                    val tagBody = if (selfClosing) body.dropLast(1) else body
                    val cut = tagBody.indexOfFirst { it.isWhitespace() }
                    val name = (if (cut < 0) tagBody else tagBody.take(cut)).trim().lowercase()
                    val attrSource = if (cut < 0) "" else tagBody.substring(cut + 1)
                    builder.open(name, attrs(attrSource), selfClosing)
                }
            }
            i = gt + 1
        }
        builder.finish()
        return out
    }

    /** Extracts the attrs we care about: src/href/alt. */
    private fun attrs(source: String): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < source.length) {
            while (i < source.length && (source[i] == ' ' || source[i] == '/')) i++
            val start = i
            while (i < source.length && source[i] != '=') {
                if (source[i] == ' ') break
                i++
            }
            if (i == start) break
            val key = source.substring(start, i).lowercase()
            if (key != "src" && key != "href" && key != "alt") {
                while (i < source.length && source[i] != ' ') i++
                continue
            }
            while (i < source.length && source[i] == ' ') i++
            if (source.getOrNull(i) != '=') {
                map[key] = ""
                continue
            }
            i++
            while (i < source.length && source[i] == ' ') i++
            val quote = source.getOrNull(i)
            val value = when (quote) {
                '"', '\'' -> {
                    val close = source.indexOf(quote, i + 1)
                    if (close < 0) source.substring(i + 1).also { i = source.length }
                    else source.substring(i + 1, close).also { i = close + 1 }
                }

                else -> {
                    val end = source.indexOf(' ', i).let { if (it < 0) source.length else it }
                    source.substring(i, end).also { i = end }
                }
            }
            map[key] = decodeHtmlEntities(value)
        }
        return map
    }

    private class Builder(private val out: MutableList<Block>) {
        private val spans = ArrayList<Span>()
        private var bold = 0
        private var italic = 0
        private var quoteDepth = 0
        private var preformatted = false
        private var skipUntil: String? = null // head/script/style/textarea
        private var pendingSpace = false
        private var cursor = 0
        private val listStack = ArrayList<Pair<String, Int>>() // "ul"|"ol" to counter

        private var headingLevel = 0
        private var blockBullet: String? = null

        fun open(name: String, attrs: Map<String, String>, selfClosing: Boolean) {
            if (skipUntil != null) return
            when (name) {
                "head", "script", "style", "textarea", "title" -> if (!selfClosing) skipUntil = name

                "b", "strong" -> {
                    flushPendingSpace()
                    bold++
                }
                "i", "em" -> {
                    flushPendingSpace()
                    italic++
                }
                "br" -> {
                    // A hard line break inside the paragraph.
                    appendSpan("\n")
                    pendingSpace = false
                }

                "img" -> {
                    val src = attrs["src"] ?: return
                    flushParagraph()
                    out += Block.Image(cursor, src)
                }

                "hr" -> {
                    flushParagraph()
                    out += Block.Ruler(cursor)
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flushParagraph()
                    headingLevel = name[1] - '0'
                }

                "p", "div", "section", "article", "aside", "header", "footer", "main",
                "nav", "figure", "figcaption", "td", "th", "caption", "dt", "dd",
                -> flushParagraph()

                "blockquote" -> {
                    flushParagraph()
                    quoteDepth++
                }

                "pre" -> {
                    flushParagraph()
                    preformatted = true
                }

                "ul" -> {
                    flushParagraph()
                    listStack += "ul" to 0
                }

                "ol" -> {
                    flushParagraph()
                    listStack += "ol" to 0
                }

                "li" -> {
                    flushParagraph()
                    val (kind, index) = listStack.lastOrNull() ?: ("ul" to 0)
                    if (kind == "ol") {
                        val next = index + 1
                        if (listStack.isNotEmpty()) listStack[listStack.size - 1] = kind to next
                        blockBullet = "$next."
                    } else {
                        blockBullet = "•"
                    }
                }

                else -> Unit
            }
        }

        fun close(name: String) {
            if (skipUntil != null) {
                if (name == skipUntil) skipUntil = null
                return
            }
            when (name) {
                "b", "strong" -> bold = (bold - 1).coerceAtLeast(0)
                "i", "em" -> italic = (italic - 1).coerceAtLeast(0)
                "blockquote" -> quoteDepth = (quoteDepth - 1).coerceAtLeast(0)
                "pre" -> {
                    flushParagraph()
                    preformatted = false
                }

                "ul", "ol" -> {
                    flushParagraph()
                    if (listStack.isNotEmpty()) listStack.removeAt(listStack.size - 1)
                }

                "li" -> flushParagraph()

                "h1", "h2", "h3", "h4", "h5", "h6" -> flushParagraph()

                "p", "div", "section", "article", "aside", "header", "footer", "main",
                "nav", "figure", "figcaption", "td", "th", "caption", "dt", "dd",
                "body", "html",
                -> flushParagraph()

                else -> Unit
            }
        }

        fun text(raw: String) {
            if (skipUntil != null || raw.isEmpty()) return
            if (preformatted) {
                appendSpan(decodeHtmlEntities(raw))
                return
            }
            val decoded = decodeHtmlEntities(raw)
            for (ch in decoded) {
                if (ch.isWhitespace()) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && spansHaveContent()) appendSpan(" ")
                    appendSpan(ch.toString())
                    pendingSpace = false
                }
            }
        }

        fun finish() = flushParagraph()

        /** Emits a deferred whitespace run before a style change, so the space stays
         *  attached to the preceding (unstyled) span. */
        private fun flushPendingSpace() {
            if (pendingSpace && spansHaveContent()) {
                appendSpan(" ")
                pendingSpace = false
            }
        }

        private fun spansHaveContent(): Boolean =
            spans.isNotEmpty() && spans.last().text.isNotEmpty() && spans.last().text.last() != '\n'

        private fun appendSpan(textPiece: String) {
            val piece = decodeInlineEntities(textPiece)
            if (spans.isEmpty()) {
                val trimmed = piece.removePrefix(" ")
                if (trimmed.isEmpty()) return
                spans += Span(trimmed, bold > 0, italic > 0)
                return
            }
            val last = spans.last()
            if (last.bold == (bold > 0) && last.italic == (italic > 0)) {
                spans[spans.size - 1] = last.copy(text = last.text + piece)
            } else {
                spans += Span(piece, bold > 0, italic > 0)
            }
        }

        private fun decodeInlineEntities(text: String): String =
            if (text.contains('&')) decodeHtmlEntities(text) else text

        private fun flushParagraph() {
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
            if (spans.isNotEmpty()) {
                val last = spans.last()
                val trimmed = last.text.trimEnd()
                if (trimmed.isEmpty()) spans.removeAt(spans.size - 1)
                else spans[spans.size - 1] = last.copy(text = trimmed)
            }
            pendingSpace = false
            if (spans.isNotEmpty()) {
                val text = spans.joinToString("") { it.text }
                val start = cursor
                cursor += text.length
                out += Block.Paragraph(
                    startOffset = start,
                    endOffset = cursor,
                    spans = spans.toList(),
                    headingLevel = headingLevel,
                    indent = headingLevel == 0 && blockBullet == null && quoteDepth == 0 && !preformatted,
                    quote = quoteDepth > 0,
                    bullet = blockBullet,
                    preformatted = preformatted,
                )
            }
            spans.clear()
            headingLevel = 0
            blockBullet = null
        }
    }
}

internal fun decodeHtmlEntities(value: String): String {
    if (!value.contains('&')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        val semi = value.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 10) {
            out.append(c)
            i++
            continue
        }
        when (val entity = value.substring(i + 1, semi)) {
            "amp" -> out.append('&')
            "lt" -> out.append('<')
            "gt" -> out.append('>')
            "quot" -> out.append('"')
            "apos" -> out.append('\'')
            "nbsp" -> out.append(' ')
            else -> if (entity.startsWith("#")) {
                val code = entity.removePrefix("#").let {
                    if (it.startsWith("x") || it.startsWith("X")) it.substring(1).toIntOrNull(16) else it.toIntOrNull()
                }
                if (code != null) out.append(code.toChar()) else out.append(value.substring(i, semi + 1))
            } else {
                out.append(value.substring(i, semi + 1))
            }
        }
        i = semi + 1
    }
    return out.toString()
}
