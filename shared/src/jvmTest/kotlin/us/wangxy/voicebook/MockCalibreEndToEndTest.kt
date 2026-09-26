package us.wangxy.voicebook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.epub.EpubBook
import us.wangxy.voicebook.reader.paginate.LineBox
import us.wangxy.voicebook.reader.paginate.LineMeasurer
import us.wangxy.voicebook.reader.paginate.PageLayout
import us.wangxy.voicebook.reader.paginate.Paginator
import us.wangxy.voicebook.reader.render.ReaderStyle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixed-metric measurer mirroring the real one's contract (see PaginatorTest). */
private class FakeLineMeasurer(private val charsPerLine: Int) : LineMeasurer {
    override fun lines(paragraph: us.wangxy.voicebook.reader.epub.Block.Paragraph, style: ReaderStyle, maxWidthPx: Float, fontScale: Float): List<LineBox> {
        val text = paragraph.spans.joinToString("") { it.text }
        if (text.isEmpty()) return emptyList()
        val count = (text.length + charsPerLine - 1) / charsPerLine
        return List(count) { line -> LineBox(style.lineHeightPx * fontScale, line * charsPerLine) }
    }
}

/**
 * End-to-end over a real HTTP server: OPDS feed → epub download → parse → paginate →
 * save anchor → reopen → restore the same page. This is the resume-reading loop the
 * Library/Reader screens drive, minus the Compose layer.
 */
class MockCalibreEndToEndTest {

    private lateinit var server: HttpServer
    private var port = 0

    private val bookBytes: ByteArray by lazy { buildEpub() }

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/opds/new") { ex -> respondAtom(ex) }
        server.createContext("/opds/cover/77") { ex ->
            ex.responseHeaders.add("Content-Type", "image/png")
            respond(ex, pngBytes())
        }
        server.createContext("/opds/download/77/EPUB/") { ex ->
            ex.responseHeaders.add("Content-Type", "application/epub+zip")
            respond(ex, bookBytes)
        }
        server.executor = null
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, bytes: ByteArray) {
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondAtom(ex: HttpExchange) {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Recently Added</title>
              <entry>
                <title>三体</title>
                <author><name>刘慈欣</name></author>
                <summary>测试用书</summary>
                <link rel="http://opds-spec.org/image" type="image/png" href="/opds/cover/77"/>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/opds/download/77/EPUB/"/>
              </entry>
            </feed>
        """.trimIndent().encodeToByteArray()
        ex.responseHeaders.add("Content-Type", "application/atom+xml")
        respond(ex, xml)
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** Four chapters of dense Chinese text plus one image chapter — exercises everything. */
    private fun buildEpub(): ByteArray {
        fun chapter(title: String, body: String) = """
            <html><body><h2>$title</h2>$body</body></html>
        """.trimIndent()

        val paragraph = "红岸基地的雷达天线在群山之间缓缓转动，像一只寻找星海的眼睛。".repeat(8)
        val chapters = (1..4).map { i ->
            if (i == 3) {
                chapter("第${i}章", "<p>${paragraph}</p><img src=\"../img/plan.png\" alt=\"图\"/><p>${paragraph}</p>")
            } else {
                chapter("第${i}章", (1..6).joinToString("") { "<p>$paragraph</p>" })
            }
        }

        val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>三体</dc:title><dc:creator>刘慈欣</dc:creator>
              </metadata>
              <manifest>
                ${chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"text/c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
                <item id="img1" href="img/plan.png" media-type="image/png"/>
              </manifest>
              <spine>${chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine>
            </package>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("META-INF/container.xml", container.encodeToByteArray())
            put("OEBPS/content.opf", opf.encodeToByteArray())
            chapters.forEachIndexed { i, html -> put("OEBPS/text/c$i.xhtml", html.encodeToByteArray()) }
            put("OEBPS/img/plan.png", pngBytes())
        }
        return out.toByteArray()
    }

    @Test
    fun fetchDownloadParseResume() = runBlocking {
        val client = HttpClient(io.ktor.client.engine.okhttp.OkHttp)
        val api = CalibreWebApi(client)
        val server = CalibreServer("http://127.0.0.1:$port")

        // 1. 书架加载
        val feed = api.newest(server)
        assertEquals(1, feed.entries.size)
        val entry = feed.entries.single()
        assertEquals("三体", entry.title)
        assertEquals(77, entry.bookId)

        // 2. 封面可取
        assertEquals("http://127.0.0.1:$port/opds/cover/77", api.coverUrl(server, entry))

        // 3. 下载 + 解析
        val bytes = api.downloadBook(server, entry.bookId, entry.epubHref, api.formatFromHref(entry.epubHref) ?: "EPUB")
        val book = EpubBook.parse(bytes)
        assertEquals("三体", book.title)
        assertEquals("刘慈欣", book.author)
        assertEquals(5, book.chapters.size) // title page + 4 chapters

        // 4. 分页（与 ReaderScreen 同一测量路径：这里用假测量器，几何一致性已由
        //    PaginatorTest 保证；此处验证的是整条恢复链路）
        val style = ReaderStyle(18f, horizontalPaddingPx = 24f, topPaddingPx = 20f, bottomPaddingPx = 28f)
        val layout = PageLayout(700f, 1100f)
        val measurer = FakeLineMeasurer(charsPerLine = 28)
        val pages = Paginator.paginate(book.chapterBlocks(2), measurer, style, layout)
        assertTrue(pages.size > 1, "chapter 2 should span several pages")

        // 5. 模拟用户翻到中间某页后退出：记录锚点
        val stoppedPage = pages.size / 2
        val savedOffset = pages[stoppedPage].anchorOffset

        // 6. 重新打开：解析同一本书，用锚点恢复
        val bookReopened = EpubBook.parse(api.downloadBook(server, entry.bookId, entry.epubHref, "EPUB"))
        val pagesReopened = Paginator.paginate(bookReopened.chapterBlocks(2), measurer, style, layout)
        val restored = Paginator.pageForOffset(pagesReopened, savedOffset)

        assertEquals(stoppedPage, restored, "must resume onto the exact page last read")
        client.close()
    }
}
