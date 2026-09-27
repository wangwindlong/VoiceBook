package us.wangxy.voicebook.rss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RssParserTest {

    private val rss2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
            <title>科技早报</title>
            <link>https://example.org/</link>
            <image><url>https://example.org/logo.png</url></image>
            <item>
                <title><![CDATA[第一篇：AI 与播客]]></title>
                <link>https://example.org/post-1</link>
                <guid isPermaLink="false">post-1-guid</guid>
                <pubDate>Tue, 10 Jun 2003 04:00:00 GMT</pubDate>
                <description>纯文本摘要 &amp; 一些实体</description>
                <content:encoded><![CDATA[<p>正文 <b>加粗</b> <img src="https://example.org/img1.jpg"/></p>]]></content:encoded>
                <enclosure url="https://example.org/ep1.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
                <title>第二篇：无 guid</title>
                <link>https://example.org/post-2</link>
                <pubDate>2024-01-15T08:30:00Z</pubDate>
                <description>另一篇</description>
            </item>
        </channel></rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
            <title>Atom 周刊</title>
            <link rel="alternate" href="https://atom.example/"/>
            <entry>
                <id>tag:atom.example,2024:1</id>
                <title>条目一</title>
                <link rel="alternate" href="https://atom.example/1"/>
                <published>2024-02-01T09:00:00Z</published>
                <summary>Atom 摘要</summary>
                <content type="html">&lt;p&gt;Atom 正文&lt;/p&gt;</content>
            </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesRss2ChannelAndItems() {
        val feed = RssParser.parse(rss2)
        assertEquals("科技早报", feed.title)
        assertEquals("https://example.org/logo.png", feed.imageUrl)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("post-1-guid", first.guid)
        assertEquals("https://example.org/post-1", first.link)
        assertEquals("https://example.org/ep1.mp3", first.audioUrl)
        assertEquals("https://example.org/img1.jpg", first.imageUrl)
        assertTrue(first.contentHtml.contains("加粗"))
        assertEquals("纯文本摘要 & 一些实体", first.summary)
        // 2003-06-10T04:00:00Z
        assertEquals(1055217600000L, first.publishedAt)
    }

    @Test
    fun missingGuidFallsBackToLink() {
        val feed = RssParser.parse(rss2)
        assertEquals("https://example.org/post-2", feed.items[1].guid)
        assertEquals(epochOf("2024-01-15T08:30:00Z"), feed.items[1].publishedAt)
    }

    @Test
    fun parsesAtomEntries() {
        val feed = RssParser.parse(atom)
        assertEquals("Atom 周刊", feed.title)
        assertEquals(1, feed.items.size)
        val entry = feed.items[0]
        assertEquals("tag:atom.example,2024:1", entry.guid)
        assertEquals("https://atom.example/1", entry.link)
        assertTrue(entry.contentHtml.contains("Atom 正文"))
        assertEquals(epochOf("2024-02-01T09:00:00Z"), entry.publishedAt)
    }

    @Test
    fun isoAndRfc1123BothParse() {
        assertEquals(1055217600000L, RssParser.parseDate("Tue, 10 Jun 2003 04:00:00 GMT"))
        assertEquals(1055217600000L, RssParser.parseDate("10 Jun 2003 04:00:00 GMT"))
        assertTrue(RssParser.parseDate("garbage") > 0, "unparseable dates fall back to now")
    }

    @Test
    fun htmlToTextStripsTags() {
        assertEquals("正文 加粗", RssParser.htmlToText("<p>正文 <b>加粗</b></p>"))
    }
}

/** ISO string → epoch helper for assertions (same parsing path as the app). */
private fun epochOf(iso: String): Long = kotlinx.datetime.Instant.parse(iso).toEpochMilliseconds()
