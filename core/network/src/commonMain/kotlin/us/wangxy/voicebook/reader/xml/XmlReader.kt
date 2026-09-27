package us.wangxy.voicebook.reader.xml

/**
 * Minimal pull XML parser in common Kotlin, enough for OPDS Atom feeds and EPUB's
 * container.xml / OPF documents. Handles declarations, comments, CDATA and the five
 * predefined entities plus numeric character references; unknown entities pass through
 * verbatim (lenient). Element names are exposed with the namespace prefix stripped, so
 * callers match on local names and stay insensitive to the prefix conventions a server
 * happens to use.
 */
class XmlReader(text: String) {

    enum class EventType { START, END, TEXT }

    var eventType: EventType = EventType.START
        private set
    var name: String = ""
        private set
    var text: String = ""
        private set

    private val attrs = HashMap<String, String>()
    private var pendingSelfClose: String? = null

    private var pos = 0
    private val src = text

    /** Advances to the next event, returning false at end of input. */
    fun next(): Boolean {
        attrs.clear()
        pendingSelfClose?.let {
            pendingSelfClose = null
            name = it
            eventType = EventType.END
            return true
        }
        while (true) {
            val lt = src.indexOf('<', pos)
            if (lt < 0) {
                if (pos < src.length) {
                    text = decodeEntities(src.substring(pos).trim());
                    pos = src.length
                    eventType = EventType.TEXT
                    return text.isNotEmpty()
                }
                return false
            }
            if (lt > pos) {
                val raw = src.substring(pos, lt)
                if (raw.isNotBlank()) {
                    text = decodeEntities(raw.trim())
                    pos = lt
                    eventType = EventType.TEXT
                    return true
                }
            }
            when (src.getOrNull(lt + 1)) {
                '?' -> pos = skipUntil(lt + 2, "?>")
                '!' -> if (src.startsWith("<!--", lt)) {
                    pos = skipUntil(lt + 4, "-->")
                } else if (src.startsWith("<![CDATA[", lt)) {
                    val end = src.indexOf("]]>", lt + 9)
                    val value = if (end < 0) src.substring(lt + 9) else src.substring(lt + 9, end)
                    pos = if (end < 0) src.length else end + 3
                    if (value.isNotEmpty()) {
                        text = value
                        eventType = EventType.TEXT
                        return true
                    }
                } else {
                    // DOCTYPE and friends: skip to the matching '>'.
                    pos = skipUntil(lt + 2, ">")
                }

                '/' -> {
                    val end = src.indexOf('>', lt + 2)
                    if (end < 0) return false
                    name = localName(src.substring(lt + 2, end).trim())
                    pos = end + 1
                    eventType = EventType.END
                    return true
                }

                else -> {
                    val end = src.indexOf('>', lt + 1)
                    if (end < 0) return false
                    val body = src.substring(lt + 1, end)
                    val selfClosing = body.endsWith("/")
                    val tagBody = if (selfClosing) body.dropLast(1) else body
                    val (tag, attrString) = splitTagName(tagBody)
                    name = localName(tag)
                    parseAttributes(attrString)
                    pos = end + 1
                    eventType = EventType.START
                    if (selfClosing) {
                        // Emit START now; a synthetic END follows on the next call.
                        pendingSelfClose = name
                    }
                    return true
                }
            }
        }
    }

    /** Attribute lookup by local name (namespace prefixes ignored). */
    fun attribute(key: String): String? = attrs[key]

    private fun splitTagName(body: String): Pair<String, String> {
        val cut = body.indexOfFirst { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        return if (cut < 0) body.trim() to "" else body.take(cut).trim() to body.substring(cut + 1)
    }

    private fun parseAttributes(source: String) {
        var i = 0
        while (i < source.length) {
            while (i < source.length && source[i].isWhitespace()) i++
            val nameStart = i
            while (i < source.length && source[i] != '=' && !source[i].isWhitespace()) i++
            if (i >= source.length || nameStart == i) break
            val attrName = localName(source.substring(nameStart, i))
            while (i < source.length && source[i].isWhitespace()) i++
            if (source.getOrNull(i) != '=') {
                if (attrName.isNotEmpty()) attrs[attrName] = ""
                continue
            }
            i++ // '='
            while (i < source.length && source[i].isWhitespace()) i++
            val quote = source.getOrNull(i)
            val value = when (quote) {
                '"', '\'' -> {
                    val close = source.indexOf(quote, i + 1)
                    if (close < 0) source.substring(i + 1).also { i = source.length }
                    else source.substring(i + 1, close).also { i = close + 1 }
                }

                else -> {
                    val end = source.indexOfFirst { it.isWhitespace() }.let {
                        if (it < 0 || it <= i) source.length else it
                    }
                    source.substring(i, end).also { i = end }
                }
            }
            attrs[attrName] = decodeEntities(value)
        }
    }

    private fun skipUntil(from: Int, marker: String): Int {
        val at = src.indexOf(marker, from)
        return if (at < 0) src.length else at + marker.length
    }

    private fun localName(tag: String): String {
        val colon = tag.indexOf(':')
        return if (colon >= 0) tag.substring(colon + 1) else tag
    }

    private fun decodeEntities(value: String): String {
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
                else -> if (entity.startsWith("#")) {
                    val code = entity.removePrefix("#").let {
                        if (it.startsWith("x") || it.startsWith("X")) it.substring(1).toIntOrNull(16) else it.toIntOrNull(10)
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
}
