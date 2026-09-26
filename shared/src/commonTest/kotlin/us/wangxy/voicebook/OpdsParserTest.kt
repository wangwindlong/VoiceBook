package us.wangxy.voicebook

import us.wangxy.voicebook.reader.opds.OpdsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpdsParserTest {

    /** Modeled on a real calibre-web /opds/new acquisition feed (trimmed). */
    private val calibreFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>urn:calibre-web:catalog</id>
          <title>Recently Added</title>
          <updated>2026-09-25T10:00:00Z</updated>
          <icon>/opds/icon.png</icon>
          <author><name>calibre-web</name></author>
          <link rel="self" href="/opds/new"/>
          <link class="search" rel="search" type="application/opensearchdescription+xml" href="/opds/osd"/>
          <link rel="next" href="/opds/new?offset=12"/>
          <entry>
            <title>三体</title>
            <id>urn:uuid:abc-1</id>
            <author><name>刘慈欣</name></author>
            <updated>2026-09-01T00:00:00Z</updated>
            <dc:format>epub</dc:format>
            <summary>地球文明向宇宙发出的第一声啼鸣。</summary>
            <link rel="http://opds-spec.org/image" type="image/jpeg" href="/opds/cover_240_240/1"/>
            <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/opds/thumb_240_240/1"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/opds/download/1/EPUB/"/>
          </entry>
          <entry>
            <title>The Three-Body Problem</title>
            <id>urn:uuid:abc-2</id>
            <author><name>Cixin Liu</name></author>
            <updated>2026-09-02T00:00:00Z</updated>
            <link rel="http://opds-spec.org/image" type="image/png" href="http://example.com/opds/cover/2"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/opds/download/2/EPUB/"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesEntriesTitlesAuthorsAndLinks() {
        val feed = OpdsParser.parse(calibreFeed)
        assertEquals(2, feed.entries.size)

        val first = feed.entries[0]
        assertEquals(1, first.bookId)
        assertEquals("三体", first.title)
        assertEquals("刘慈欣", first.author)
        assertEquals("地球文明向宇宙发出的第一声啼鸣。", first.summary)
        assertEquals("/opds/cover_240_240/1", first.coverHref)
        assertEquals("/opds/download/1/EPUB/", first.epubHref)

        val second = feed.entries[1]
        assertEquals(2, second.bookId)
        assertEquals("http://example.com/opds/cover/2", second.coverHref)
    }

    @Test
    fun extractsNextOffset() {
        val feed = OpdsParser.parse(calibreFeed)
        assertEquals(12, feed.nextOffset)

        val exhausted = OpdsParser.parse(calibreFeed.replace("<link rel=\"next\" href=\"/opds/new?offset=12\"/>", ""))
        assertNull(exhausted.nextOffset)
    }

    @Test
    fun recoversBookIdFromAllHrefShapes() {
        assertEquals(42, OpdsParser.bookIdFrom("/opds/download/42/EPUB/"))
        assertEquals(7, OpdsParser.bookIdFrom("http://host:8083/opds/cover/7"))
        assertEquals(-1, OpdsParser.bookIdFrom(null))
    }

    @Test
    fun toleratesEntryWithoutLinks() {
        val feed = OpdsParser.parse(
            """
            <feed><entry><title>断网之书</title><author><name>某人</name></author></entry></feed>
            """.trimIndent(),
        )
        assertEquals(1, feed.entries.size)
        assertEquals(-1, feed.entries[0].bookId)
        assertNull(feed.entries[0].coverHref)
        assertNull(feed.entries[0].epubHref)
    }
}
