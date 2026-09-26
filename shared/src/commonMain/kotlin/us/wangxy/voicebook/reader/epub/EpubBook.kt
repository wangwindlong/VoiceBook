package us.wangxy.voicebook.reader.epub

import kotlinx.io.IOException
import us.wangxy.voicebook.reader.xml.XmlReader

/**
 * One EPUB file held in memory. Resolution follows the spec path: META-INF/container.xml
 * → OPF package → manifest items + spine order → per-chapter XHTML parsed lazily into
 * blocks. Hrefs inside the OPF are relative to the OPF's own directory, so they are
 * normalized to zip-entry paths once at load. A synthetic chapter 0 with the book title
 * (and cover image when the OPF marks one) fronts the spine, so readers always start on
 * a title page and chapter numbering in the UI matches the spine.
 */
class EpubBook internal constructor(private val zip: ZipReader, private val opfDir: String) : ReadableBook {

    class Chapter(val title: String, internal val href: String?) {
        internal var blocks: List<Block> = emptyList()
    }

    val title: String
    val author: String
    override val chapters: List<Chapter>
    val coverImageBytes: ByteArray?

    /** Total plain-text character count across chapters (progress denominator). */
    val totalChars: Int get() = chapters.sumOf { it.blocks.sumOf { b -> b.endOffset - b.startOffset } }

    private val opfPath: String

    init {
        opfPath = resolveOpfPath()
        val opf = zip.read(opfPath).decodeToString()

        val metadata = XmlSection(opf, "metadata")
        title = metadata.text("title").ifBlank { "未命名" }
        author = metadata.text("creator").ifBlank { "" }

        val manifest = XmlSection(opf, "manifest")
        val items = HashMap<String, ManifestItem>()
        while (manifest.nextItem()) {
            items[manifest.id] = ManifestItem(manifest.id, manifest.href, manifest.mediaType, manifest.properties)
        }

        // Cover: either a properties="cover-image" item or a meta name="cover" content=idref.
        val coverId = metadata.attr("cover", "content")
        coverImageBytes = (items[coverId]?.takeIf { it.properties.contains("cover-image") } ?: items[coverId])
            ?.let { imageBytesInternal(it.href) }

        val spine = XmlSection(opf, "spine")
        val parsed = ArrayList<Chapter>()
        var synthetic = Chapter(title, null)
        synthetic.blocks = buildList {
            add(Block.Paragraph(0, title.length, listOf(Span(title)), headingLevel = 1))
            author.takeIf { it.isNotBlank() }?.let {
                add(Block.Paragraph(title.length, title.length + it.length, listOf(Span(it))))
            }
        }
        parsed += synthetic

        while (spine.nextItem()) {
            val item = items[spine.idref] ?: continue
            if (item.mediaType != "application/xhtml+xml" && item.mediaType != "text/html") continue
            parsed += Chapter(item.href.substringAfterLast('/'), normalizeHref(item.href))
        }
        chapters = parsed
    }

    /** Raw bytes for a manifest-referenced resource (images inside chapters). */
    override fun resourceBytes(hrefInChapter: String, chapterHref: String?): ByteArray? {
        val full = resolve(chapterHref, hrefInChapter) ?: return null
        return zip.readOrNull(full) ?: zip.readOrNull(full.substringAfterLast('/'))
    }

    internal fun imageBytesInternal(manifestHref: String): ByteArray? {
        val full = resolve(null, manifestHref) ?: return null
        return zip.readOrNull(full)
    }

    override fun chapterBlocks(index: Int): List<Block> {
        val chapter = chapters.getOrNull(index) ?: return emptyList()
        if (chapter.blocks.isNotEmpty() || chapter.href == null) return chapter.blocks
        val html = zip.readOrNull(chapter.href)?.decodeToString() ?: return emptyList()
        chapter.blocks = HtmlParser.parse(html)
        return chapter.blocks
    }

    private fun resolveOpfPath(): String {
        val container = zip.read("META-INF/container.xml").decodeToString()
        val reader = ContainerReader(container)
        while (reader.next()) {
            if (reader.name == "rootfile") {
                reader.fullPath?.let { return normalizeZipPath(it) }
            }
        }
        throw EpubFormatException("container.xml has no rootfile")
    }

    /** OPF hrefs are relative to the OPF's directory; container paths are zip-root-relative. */
    private fun normalizeHref(href: String): String = resolve(null, href) ?: href

    private fun resolve(baseChapterHref: String?, href: String): String? {
        val cleaned = href.substringBefore('#')
        if (cleaned.isEmpty()) return null
        val baseDir = when {
            baseChapterHref != null -> baseChapterHref.substringBeforeLast('/', "")
            else -> opfDir
        }
        val combined = if (baseDir.isEmpty()) cleaned else "$baseDir/$cleaned"
        return normalizeZipPath(combined)
    }

    private class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    /** Thin scanner over one OPF section: iterates child elements of <sectionName>. */
    private class XmlSection(private val xml: String, private val section: String) {
        private val reader = XmlReader(xml)
        private var inside = false
        private var depth = 0

        var id = ""
        var idref = ""
        var href = ""
        var mediaType = ""
        var properties = ""

        fun nextItem(): Boolean {
            while (reader.next()) {
                when (reader.eventType) {
                    XmlReader.EventType.START -> {
                        if (inside) {
                            if (reader.name == "item" || reader.name == "itemref" || reader.name == "meta") {
                                id = reader.attribute("id") ?: ""
                                idref = reader.attribute("idref") ?: ""
                                href = reader.attribute("href") ?: ""
                                mediaType = reader.attribute("media-type") ?: ""
                                properties = reader.attribute("properties") ?: ""
                                return true
                            }
                            depth++
                        } else if (reader.name == section) {
                            inside = true
                            depth = 0
                        }
                    }

                    XmlReader.EventType.END -> {
                        if (inside) {
                            if (reader.name == section && depth == 0) return false
                            if (depth > 0) depth--
                        }
                    }

                    XmlReader.EventType.TEXT -> Unit
                }
            }
            return false
        }

        fun text(name: String): String {
            val scanner = XmlReader(xml)
            var inSection = false
            while (scanner.next()) {
                when (scanner.eventType) {
                    XmlReader.EventType.START -> {
                        if (!inSection && scanner.name == section) inSection = true
                        else if (inSection && scanner.name == name) {
                            val sb = StringBuilder()
                            while (scanner.next()) {
                                when (scanner.eventType) {
                                    XmlReader.EventType.TEXT -> sb.append(scanner.text)
                                    XmlReader.EventType.END -> return sb.toString()
                                    XmlReader.EventType.START -> Unit
                                }
                            }
                        }
                    }

                    XmlReader.EventType.END -> if (inSection && scanner.name == section) return ""
                    XmlReader.EventType.TEXT -> Unit
                }
            }
            return ""
        }

        /** First <meta name="[name]"> inside the section; returns its [attrName] attribute. */
        fun attr(name: String, attrName: String): String {
            val scanner = XmlReader(xml)
            var inSection = false
            while (scanner.next()) {
                when (scanner.eventType) {
                    XmlReader.EventType.START -> {
                        if (!inSection && scanner.name == section) inSection = true
                        else if (inSection && scanner.name == "meta" && scanner.attribute("name") == name) {
                            return scanner.attribute(attrName) ?: ""
                        }
                    }

                    XmlReader.EventType.END -> if (inSection && scanner.name == section) return ""
                    XmlReader.EventType.TEXT -> Unit
                }
            }
            return ""
        }
    }

    private class ContainerReader(xml: String) {
        private val reader = XmlReader(xml)
        var name = ""
            private set

        fun next(): Boolean {
            while (reader.next()) {
                if (reader.eventType == XmlReader.EventType.START) {
                    name = reader.name
                    return true
                }
            }
            return false
        }

        val fullPath: String? get() = if (name == "rootfile") reader.attribute("full-path") else null
    }

    companion object {
        fun parse(bytes: ByteArray): EpubBook {
            val zip = ZipReader(bytes)
            val opfPath = zip.entryNames().firstOrNull { it.endsWith(".opf") }
                ?: throw EpubFormatException("no .opf in archive")
            val opfDir = opfPath.substringBeforeLast('/', "")
            return EpubBook(zip, opfDir)
        }

        fun parseOrNull(bytes: ByteArray): EpubBook? = runCatching { parse(bytes) }.getOrNull()

        private fun normalizeZipPath(path: String): String {
            val out = ArrayList<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out += part
                }
            }
            return out.joinToString("/")
        }
    }
}

class EpubFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** zip.readOrNull is exercised via [ZipReader]; this helper keeps call sites tidy. */
private fun ZipReader.readOrNull(name: String): ByteArray? =
    runCatching { read(name) }.getOrNull()
