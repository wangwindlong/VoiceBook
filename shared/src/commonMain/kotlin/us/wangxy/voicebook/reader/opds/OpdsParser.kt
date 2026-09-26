package us.wangxy.voicebook.reader.opds

import us.wangxy.voicebook.reader.xml.XmlReader

/**
 * One book entry out of a calibre-web OPDS acquisition feed. [bookId] is the calibre-web
 * numeric database id, recovered from the cover or download hrefs; [coverHref] and
 * [epubHref] are the raw hrefs as the server wrote them (root-relative or absolute).
 */
data class OpdsEntry(
    val bookId: Int,
    val title: String,
    val author: String,
    val summary: String,
    val coverHref: String?,
    val epubHref: String?,
)

data class OpdsFeed(
    val entries: List<OpdsEntry>,
    /** Offset to request for the next page, null when the feed is exhausted. */
    val nextOffset: Int?,
)

/**
 * Parses OPDS 1.2 acquisition feeds as emitted by calibre-web (Atom + dc + opds-spec
 * links). Lenient: unknown elements are ignored and link roles are matched by suffix so
 * alternative rel spellings (e.g. "http://opds-spec.org/acquisition") still work.
 */
object OpdsParser {

    fun parse(xml: String): OpdsFeed {
        val reader = XmlReader(xml)
        val entries = ArrayList<OpdsEntry>()
        var nextOffset: Int? = null

        while (reader.next()) {
            if (reader.eventType != XmlReader.EventType.START) continue
            when (reader.name) {
                "entry" -> entries += parseEntry(reader)
                "link" -> if (reader.attribute("rel") == "next") {
                    nextOffset = offsetFromHref(reader.attribute("href"))
                }
            }
        }
        return OpdsFeed(entries, nextOffset)
    }

    private fun parseEntry(reader: XmlReader): OpdsEntry {
        var title = ""
        var author = ""
        var summary = ""
        var coverHref: String? = null
        var thumbnailHref: String? = null
        var epubHref: String? = null
        var anyAcquisitionHref: String? = null

        loop@ while (reader.next()) {
            when (reader.eventType) {
                XmlReader.EventType.START -> when (reader.name) {
                    "title" -> title = readerText(reader)
                    "name" -> if (author.isEmpty()) author = readerText(reader)
                    "summary", "content" -> if (summary.isEmpty()) summary = readerText(reader)
                    "link" -> {
                        val rel = reader.attribute("rel") ?: ""
                        val type = reader.attribute("type") ?: ""
                        val href = reader.attribute("href") ?: ""
                        when {
                            type.startsWith("image/") -> when {
                                rel.endsWith("/thumbnail") -> thumbnailHref = thumbnailHref ?: href
                                else -> coverHref = coverHref ?: href
                            }

                            rel.endsWith("acquisition") -> {
                                if (type.contains("epub") || type.contains("octet-stream")) {
                                    epubHref = epubHref ?: href
                                } else {
                                    anyAcquisitionHref = anyAcquisitionHref ?: href
                                }
                            }
                        }
                    }
                    // Nested <author> etc. keep their element text; nothing else to do.
                    else -> Unit
                }

                XmlReader.EventType.END -> if (reader.name == "entry") break@loop
                XmlReader.EventType.TEXT -> Unit
            }
        }
        return OpdsEntry(
            bookId = bookIdFrom(coverHref ?: thumbnailHref ?: epubHref ?: anyAcquisitionHref),
            title = title,
            author = author,
            summary = summary.trim(),
            coverHref = coverHref ?: thumbnailHref,
            epubHref = epubHref ?: anyAcquisitionHref,
        )
    }

    /** Reads the element's text content up to its matching end tag. */
    private fun readerText(reader: XmlReader): String {
        val sb = StringBuilder()
        while (reader.next()) {
            when (reader.eventType) {
                XmlReader.EventType.TEXT -> sb.append(reader.text)
                XmlReader.EventType.END -> return sb.toString()
                XmlReader.EventType.START -> Unit // nested markup skipped
            }
        }
        return sb.toString()
    }

    internal fun offsetFromHref(href: String?): Int? {
        if (href == null) return null
        val query = href.substringAfter('?', "")
        for (param in query.split('&')) {
            val (key, value) = param.split('=', limit = 2)
            if (key == "offset") return value.toIntOrNull()
        }
        return null
    }

    internal fun bookIdFrom(href: String?): Int {
        if (href == null) return -1
        // Hrefs look like /opds/cover/123 or /opds/download/123/EPUB/; grab the first
        // all-digit run after a slash.
        var i = 0
        while (i < href.length) {
            if (href[i].isDigit() && (i == 0 || href[i - 1] == '/')) {
                var j = i
                while (j < href.length && href[j].isDigit()) j++
                return href.substring(i, j).toInt()
            }
            i++
        }
        return -1
    }
}
