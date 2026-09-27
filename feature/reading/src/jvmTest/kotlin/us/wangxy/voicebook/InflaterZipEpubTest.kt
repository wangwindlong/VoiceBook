package us.wangxy.voicebook

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.io.Buffer
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.epub.Inflater
import us.wangxy.voicebook.reader.epub.ZipReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun Buffer.writeAll(bytes: ByteArray) {
    write(bytes, 0, bytes.size)
}

class InflaterZipTest {

    private fun inflate(raw: ByteArray, expected: Int = -1): ByteArray {
        val buffer = Buffer()
        buffer.writeAll(raw)
        return Inflater.inflate(buffer, expected)
    }

    @Test
    fun deflatedRandomDataRoundTrips() {
        val random = Random(42)
        repeat(30) { iteration ->
            val size = random.nextInt(1, 200_000)
            val original = ByteArray(size).also { random.nextBytes(it) }
            val deflater = Deflater(random.nextInt(0, 10), true)
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, deflater).use { it.write(original) }
            val inflated = inflate(out.toByteArray(), size)
            assertContentEquals(original, inflated, "iteration $iteration size $size")
        }
    }

    @Test
    fun highlyCompressibleAndEdgeSizes() {
        val cases = listOf(
            ByteArray(0),                       // empty
            ByteArray(1),                       // 1 byte
            "ab".repeat(100_000).encodeToByteArray(), // long runs (RLE-ish matches)
            ('a'..'z').joinToString("").repeat(1000).encodeToByteArray(), // repetitive text
            Random(7).nextBytes(70001),         // prime-ish odd size
        )
        for (original in cases) {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, Deflater(9, true)).use { it.write(original) }
            assertContentEquals(original, inflate(out.toByteArray(), original.size))
        }
    }

    @Test
    fun sizeMismatchThrows() {
        val original = "hello".repeat(1000).encodeToByteArray()
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(9, true)).use { it.write(original) }
        assertFailsWith<kotlinx.io.IOException> { inflate(out.toByteArray(), original.size + 1) }
    }
}

class ZipReaderTest {

    private fun buildZip(entries: Map<String, ByteArray>, method: Int = ZipEntry.DEFLATED): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = method
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsStoredAndDeflatedEntries() {
        val payload1 = "纯文本内容：三体是一本科幻小说。".repeat(50).encodeToByteArray()
        val payload2 = Random(3).nextBytes(50_000)
        val bytes = buildZip(
            mapOf(
                "dir/" to ByteArray(0), // directory entry is ignored
                "text/toc.xml" to payload1,
                "bin/image.png" to payload2,
            ),
        )
        val zip = ZipReader(bytes)
        assertEquals(listOf("text/toc.xml", "bin/image.png"), zip.entryNames())
        assertContentEquals(payload1, zip.read("text/toc.xml"))
        assertContentEquals(payload2, zip.read("bin/image.png"))
        assertTrue(zip.contains("text/toc.xml"))
    }

    @Test
    fun storedMethodEntries() {
        val payload = "stored".repeat(100).encodeToByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("a.txt").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = java.util.zip.CRC32().apply { update(payload) }.value
            }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        val zip = ZipReader(out.toByteArray())
        assertContentEquals(payload, zip.read("a.txt"))
    }

    @Test
    fun missingEntryThrows() {
        val zip = ZipReader(buildZip(mapOf("a.txt" to "x".encodeToByteArray())))
        assertFailsWith<kotlinx.io.IOException> { zip.read("nope.txt") }
    }
}

class EpubBookTest {

    private fun minimalEpub(chapterBody: String, withCover: Boolean = false): ByteArray {
        val container = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent()

        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>三体</dc:title>
                <dc:creator>刘慈欣</dc:creator>
                ${if (withCover) "<meta name=\"cover\" content=\"cover-img\"/>" else ""}
              </metadata>
              <manifest>
                <item id="chap1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="chap2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                ${if (withCover) "<item id=\"cover-img\" href=\"img/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>" else ""}
              </manifest>
              <spine>
                <itemref idref="chap1"/>
                <itemref idref="chap2"/>
              </spine>
            </package>""".trimIndent()

        val chapter1 = """<?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>c1</title></head>
              <body>
                <h2>第一章</h2>
                <p>${chapterBody}</p>
                ${if (withCover) "<img src=\"../img/cover.jpg\" alt=\"封面\"/>" else ""}
              </body>
            </html>""".trimIndent()

        val chapter2 = """<html><body><p>第二章内容。</p></body></html>"""

        // Fake 1x1 JPEG (SOI + SOF0 with 1x1 dimensions) for the cover path.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("META-INF/container.xml", container.encodeToByteArray())
            put("OEBPS/content.opf", opf.encodeToByteArray())
            put("OEBPS/text/chapter1.xhtml", chapter1.encodeToByteArray())
            put("OEBPS/text/chapter2.xhtml", chapter2.encodeToByteArray())
            if (withCover) put("OEBPS/img/cover.jpg", jpeg)
        }
        return out.toByteArray()
    }

    @Test
    fun parsesMetadataSpineAndChapters() {
        val book = EpubBook.parse(minimalEpub("这是正文内容。"))
        assertEquals("三体", book.title)
        assertEquals("刘慈欣", book.author)
        // Index 0 is the synthetic title page.
        assertEquals(3, book.chapters.size)
        assertEquals("三体", book.chapters[0].title)

        val blocks = book.chapterBlocks(1)
        val heading = blocks.filterIsInstance<us.wangxy.voicebook.reader.epub.Block.Paragraph>().first { it.headingLevel == 2 }
        assertEquals("第一章", heading.spans.single().text)
        val body = blocks.filterIsInstance<us.wangxy.voicebook.reader.epub.Block.Paragraph>().last()
        assertEquals("这是正文内容。", body.spans.single().text)
        assertTrue(book.chapterBlocks(2).isNotEmpty())
    }

    @Test
    fun resolvesRelativeImageHrefFromChapterDirectory() {
        val book = EpubBook.parse(minimalEpub("正文", withCover = true))
        assertNotNull(book.coverImageBytes)
        val blocks = book.chapterBlocks(1)
        val image = blocks.filterIsInstance<us.wangxy.voicebook.reader.epub.Block.Image>().single()
        val bytes = book.resourceBytes(image.src, book.chapters[1].href)
        assertNotNull(bytes)
        assertEquals(15, bytes.size)
    }

    @Test
    fun notAZipFailsCleanly() {
        assertFailsWith<Exception> { EpubBook.parse("definitely not a zip".encodeToByteArray()) }
        assertEquals(null, EpubBook.parseOrNull("nope".encodeToByteArray()))
    }
}
