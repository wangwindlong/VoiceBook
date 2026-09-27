package us.wangxy.voicebook.rss

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import us.wangxy.voicebook.reader.xml.XmlReader
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Protocol-level parse result; the repository maps these into its stores. */
data class RssParsedFeed(
    val title: String,
    val link: String,
    val siteUrl: String,
    val imageUrl: String?,
    val items: List<RssParsedItem>,
)

data class RssParsedItem(
    val guid: String,
    val title: String,
    val link: String,
    val publishedAt: Long,
    val summary: String,
    val contentHtml: String,
    val imageUrl: String?,
    val audioUrl: String?,
)

/**
 * Lenient RSS 2.0 + Atom parser on the project's own pull XmlReader (the same one
 * OPDS uses). Element names are matched on local names, so namespace prefixes
 * (content:encoded, media:content) work out of the box.
 */
object RssParser {

    fun parse(text: String): RssParsedFeed {
        val reader = XmlReader(text)
        var isAtom = false
        while (reader.next()) {
            if (reader.eventType == XmlReader.EventType.START) {
                isAtom = reader.name.equals("feed", ignoreCase = true)
                break
            }
        }
        return if (isAtom) parseAtom(reader) else parseRss(reader)
    }

    // ---- RSS 2.0 ----------------------------------------------------------

    private fun parseRss(reader: XmlReader): RssParsedFeed {
        var title = ""
        var link = ""
        var imageUrl: String? = null
        val items = ArrayList<RssParsedItem>()

        var inItem = false
        var inImage = false
        var guid = ""
        var itemTitle = ""
        var itemLink = ""
        var pubDate = ""
        var description = ""
        var encoded = ""
        var audioUrl: String? = null
        var itemImage: String? = null

        while (reader.next()) {
            when (reader.eventType) {
                XmlReader.EventType.START -> when (reader.name.lowercase()) {
                    "item" -> {
                        inItem = true
                        guid = ""; itemTitle = ""; itemLink = ""; pubDate = ""
                        description = ""; encoded = ""; audioUrl = null; itemImage = null
                    }
                    "image" -> if (!inItem) inImage = true
                    "enclosure" -> if (inItem) {
                        val type = reader.attribute("type").orEmpty()
                        val url = reader.attribute("url")
                        if (url != null) {
                            when {
                                type.startsWith("audio") -> audioUrl = url
                                type.startsWith("image") -> itemImage = url
                            }
                        }
                    }
                    "content" -> if (inItem) {
                        val url = reader.attribute("url")
                        val medium = reader.attribute("medium").orEmpty()
                        val type = reader.attribute("type").orEmpty()
                        if (url != null && medium == "image" || url != null && type.startsWith("image")) itemImage = url
                        if (url != null && medium == "audio" || url != null && type.startsWith("audio")) audioUrl = url
                    }
                }

                XmlReader.EventType.TEXT -> when (reader.name.lowercase()) {
                    "title" -> if (inItem) itemTitle = reader.text else if (title.isEmpty()) title = reader.text
                    "link" -> when {
                        inItem && itemLink.isEmpty() -> itemLink = reader.text
                        !inItem && inImage && imageUrl == null -> imageUrl = reader.text
                        !inItem && link.isEmpty() && !reader.text.isBlank() -> link = reader.text
                    }
                    "url" -> if (!inItem && inImage) imageUrl = reader.text
                    "guid" -> if (inItem && guid.isEmpty()) guid = reader.text
                    "pubdate", "date" -> if (inItem && pubDate.isEmpty()) pubDate = reader.text
                    "description" -> if (inItem) description = reader.text
                    "encoded" -> if (inItem) encoded = reader.text
                }

                XmlReader.EventType.END -> when (reader.name.lowercase()) {
                    "item" -> {
                        inItem = false
                        val html = encoded.ifBlank { description }
                        items += RssParsedItem(
                            guid = guid.ifBlank { itemLink.ifBlank { "${title}:${items.size}" } },
                            title = itemTitle,
                            link = itemLink,
                            publishedAt = parseDate(pubDate),
                            summary = htmlToText(description.ifBlank { html }).take(400),
                            contentHtml = html,
                            imageUrl = itemImage ?: firstImgSrc(html),
                            audioUrl = audioUrl,
                        )
                    }
                    "image" -> inImage = false
                    "channel" -> link = link.ifBlank { "" }
                }
            }
        }
        return RssParsedFeed(title = title, link = link, siteUrl = link, imageUrl = imageUrl, items = items)
    }

    // ---- Atom -------------------------------------------------------------

    private fun parseAtom(reader: XmlReader): RssParsedFeed {
        var title = ""
        var feedLink = ""
        val items = ArrayList<RssParsedItem>()

        var inEntry = false
        var id = ""
        var entryTitle = ""
        var entryLink = ""
        var entryAudio: String? = null
        var published = ""
        var summary = ""
        var content = ""

        while (reader.next()) {
            when (reader.eventType) {
                XmlReader.EventType.START -> when (reader.name.lowercase()) {
                    "entry" -> {
                        inEntry = true
                        id = ""; entryTitle = ""; entryLink = ""
                        entryAudio = null; published = ""; summary = ""; content = ""
                    }
                    "link" -> {
                        val href = reader.attribute("href")
                        val rel = reader.attribute("rel") ?: "alternate"
                        val type = reader.attribute("type").orEmpty()
                        if (href != null) {
                            when {
                                inEntry && (rel == "enclosure" || type.startsWith("audio")) -> entryAudio = href
                                inEntry && entryLink.isEmpty() && rel == "alternate" -> entryLink = href
                                !inEntry && feedLink.isEmpty() && rel == "alternate" -> feedLink = href
                            }
                        }
                    }
                }

                XmlReader.EventType.TEXT -> when (reader.name.lowercase()) {
                    "title" -> if (inEntry) entryTitle = reader.text else if (title.isEmpty()) title = reader.text
                    "id" -> if (inEntry && id.isEmpty()) id = reader.text
                    "published", "updated" -> if (inEntry && published.isEmpty()) published = reader.text
                    "summary" -> if (inEntry) summary = reader.text
                    "content" -> if (inEntry) content = reader.text
                }

                XmlReader.EventType.END -> when (reader.name.lowercase()) {
                    "entry" -> {
                        inEntry = false
                        val html = content.ifBlank { summary }
                        items += RssParsedItem(
                            guid = id.ifBlank { entryLink },
                            title = entryTitle,
                            link = entryLink,
                            publishedAt = parseDate(published),
                            summary = htmlToText(summary.ifBlank { html }).take(400),
                            contentHtml = html,
                            imageUrl = firstImgSrc(html),
                            audioUrl = entryAudio,
                        )
                    }
                }
            }
        }
        return RssParsedFeed(title = title, link = feedLink, siteUrl = feedLink, imageUrl = null, items = items)
    }

    // ---- helpers ----------------------------------------------------------

    /** RFC 1123 / RFC 822 / ISO 8601, falling back to now (feed order still works). */
    fun parseDate(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return now()
        // ISO 8601 (2024-01-15T08:00:00Z / +08:00 / fractional seconds)
        if (value.length >= 10 && value[4] == '-' && value[7] == '-') {
            Instant.parse(value).toEpochMilliseconds().let { return it }
        }
        // RFC 1123: "Tue, 10 Jun 2003 04:00:00 GMT" (seconds sometimes omitted)
        val tokens = value.replace(",", "").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 4) {
            runCatching {
                // With weekday: day/mon/year start at index 1; without, index 0.
                val hasWeekday = tokens[0].toIntOrNull() == null
                val day = tokens[if (hasWeekday) 1 else 0].toInt()
                val month = months[tokens[if (hasWeekday) 2 else 1].lowercase().take(3)] ?: return now()
                val year = tokens[if (hasWeekday) 3 else 2].toInt()
                var hour = 0
                var minute = 0
                var second = 0
                var offsetSeconds = 0
                if (tokens.size >= (if (hasWeekday) 5 else 4)) {
                    val timeParts = tokens[if (hasWeekday) 4 else 3].split(":")
                    hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 0
                    minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
                    second = timeParts.getOrNull(2)?.toIntOrNull() ?: 0
                }
                val tz = tokens.getOrNull(if (hasWeekday) 5 else 4) ?: "GMT"
                offsetSeconds = when {
                    tz == "GMT" || tz == "UTC" || tz == "Z" -> 0
                    tz.startsWith("+") || tz.startsWith("-") -> {
                        val sign = if (tz.startsWith("-")) -1 else 1
                        val digits = tz.drop(1).replace(":", "")
                        val h = digits.take(2).toIntOrNull() ?: 0
                        val m = digits.drop(2).toIntOrNull() ?: 0
                        sign * (h * 3600 + m * 60)
                    }
                    else -> zones[tz.uppercase()] ?: 0
                }
                val base = LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                return base + hour * 3_600_000L + minute * 60_000L + second * 1_000L - offsetSeconds * 1_000L
            }
        }
        return now()
    }

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private val zones = mapOf(
        "EST" to -5 * 3600, "EDT" to -4 * 3600, "CST" to -6 * 3600, "CDT" to -5 * 3600,
        "MST" to -7 * 3600, "MDT" to -6 * 3600, "PST" to -8 * 3600, "PDT" to -7 * 3600,
        "BST" to 1 * 3600, "CET" to 1 * 3600, "CEST" to 2 * 3600,
    )

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** Strips tags/entities for list snippets and search text. */
    fun htmlToText(html: String): String {
        if (!html.contains('<')) return decodeBasic(html)
        val builder = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                builder.append(html.substring(i))
                break
            }
            builder.append(html.substring(i, lt))
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            i = gt + 1
        }
        return decodeBasic(builder.toString()).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeBasic(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun firstImgSrc(html: String): String? {
        val lower = html.lowercase()
        val img = lower.indexOf("<img")
        if (img < 0) return null
        val srcKey = lower.indexOf("src=", img)
        if (srcKey < 0) return null
        val valueStart = srcKey + 4
        val quote = html.getOrNull(valueStart)
        val value = if (quote == '"' || quote == '\'') {
            val end = html.indexOf(quote, valueStart + 1)
            if (end < 0) return null
            html.substring(valueStart + 1, end)
        } else {
            var end = valueStart
            while (end < html.length && !html[end].isWhitespace() && html[end] != '>') end++
            html.substring(valueStart, end)
        }
        return value.takeIf { it.startsWith("http") }
    }
}
