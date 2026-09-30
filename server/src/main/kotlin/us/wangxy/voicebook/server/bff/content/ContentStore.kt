package us.wangxy.voicebook.server.bff.content

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.calibre.query
import us.wangxy.voicebook.server.bff.calibre.update
import us.wangxy.voicebook.server.bff.calibre.transaction

/** BFF-owned data. Never writes to calibre's metadata.db or upstream comment tables. */
class ContentStore(private val db: File, private val files: File) {
    private fun connect(): Connection {
        db.absoluteFile.parentFile.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").also { connection ->
            connection.createStatement().use { s ->
                s.execute("PRAGMA busy_timeout=5000")
                s.execute("CREATE TABLE IF NOT EXISTS uploads (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, format TEXT NOT NULL, category TEXT NOT NULL, file TEXT NOT NULL UNIQUE)")
                s.execute("CREATE TABLE IF NOT EXISTS reactions (article TEXT NOT NULL, owner TEXT NOT NULL, PRIMARY KEY(article,owner))")
                s.execute("CREATE TABLE IF NOT EXISTS feed_categories (owner TEXT NOT NULL, feed TEXT NOT NULL, category TEXT NOT NULL, PRIMARY KEY(owner,feed))")
                s.execute("CREATE TABLE IF NOT EXISTS progress (owner TEXT NOT NULL, book INTEGER NOT NULL, format TEXT NOT NULL, position TEXT NOT NULL, percent REAL, updated TEXT NOT NULL, PRIMARY KEY(owner,book))")
            }
        }
    }
    private suspend fun <T> sql(block: (Connection) -> T): T = withContext(Dispatchers.IO) { connect().use(block) }

    /** Negative public IDs avoid collisions with calibre and are stable across clients. */
    suspend fun createUpload(): File = withContext(Dispatchers.IO) { files.mkdirs(); File.createTempFile("upload-", ".part", files) }

    suspend fun add(owner: String, filename: String, title: String, author: String, category: String, bytes: ByteArray): CalibreBook {
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD) throw BffException.badRequest("图书大小需在 1 字节至 64MB 之间")
        val staged = createUpload()
        try {
            withContext(Dispatchers.IO) { staged.writeBytes(bytes) }
            return addFile(owner, filename, title, author, category, staged)
        } finally { staged.delete() }
    }
    /** Streams the request to a temporary file first, avoiding several 64MB heap copies. */
    suspend fun addFile(owner: String, filename: String, title: String, author: String, category: String, source: File): CalibreBook = withContext(Dispatchers.IO) {
        val format = filename.substringAfterLast('.', "").uppercase()
        if (format !in setOf("EPUB", "PDF", "TXT")) throw BffException.badRequest("支持 EPUB、PDF、TXT")
        if (source.length() !in 1..MAX_UPLOAD.toLong()) throw BffException.badRequest("图书大小需在 1 字节至 64MB 之间")
        if (title.isBlank() || title.length > 200 || author.length > 200 || category.length > 40) throw BffException.badRequest("书籍信息过长或书名为空")
        val header = source.inputStream().use { it.readNBytes(5) }
        if (format == "PDF" && !header.decodeToString().startsWith("%PDF-")) throw BffException.badRequest("不是有效的 PDF 文件")
        if (format == "EPUB") {
            val valid = runCatching {
                java.util.zip.ZipFile(source).use { zip ->
                    val mime = zip.getEntry("mimetype") ?: return@use false
                    mime.size <= 100 && zip.getInputStream(mime).use { it.readNBytes(100).decodeToString().trim() } == "application/epub+zip" && zip.getEntry("META-INF/container.xml") != null
                }
            }.getOrDefault(false)
            if (!valid) throw BffException.badRequest("不是有效的 EPUB 文件")
        }
        files.mkdirs()
        val name = "${UUID.randomUUID()}.${format.lowercase()}"
        val file = File(files, name)
        try {
            java.nio.file.Files.move(source.toPath(), file.toPath())
            sql { c ->
                c.update("INSERT INTO uploads(owner,title,author,format,category,file) VALUES(?,?,?,?,?,?)", owner, title.trim(), author.trim(), format, category, name)
                val id = c.query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
                CalibreBook(-id, title.trim(), listOf(author.trim()).filter(String::isNotBlank), tags = listOf(category), formats = listOf(format))
            }
        } catch (e: Throwable) { file.delete(); throw e }
    }
    suspend fun books(owner: String, q: String? = null): List<CalibreBook> = sql { c ->
        c.query("SELECT * FROM uploads WHERE owner=? ORDER BY id DESC", owner) {
            CalibreBook(-it.getLong("id"), it.getString("title"), listOf(it.getString("author")).filter(String::isNotBlank),
                tags = listOf(it.getString("category")), formats = listOf(it.getString("format")))
        }.filter { q.isNullOrBlank() || it.title.contains(q, true) || it.authors.any { author -> author.contains(q, true) } }
    }
    suspend fun book(owner: String, id: Long): CalibreBook = books(owner).firstOrNull { it.id == id }
        ?: throw BffException.notFound("书籍不存在")
    suspend fun file(owner: String, id: Long, format: String): File = sql { c ->
        val name = c.query("SELECT file FROM uploads WHERE id=? AND owner=? AND format=?", -id, owner, format.uppercase()) { it.getString(1) }.firstOrNull()
            ?: throw BffException.notFound("书籍格式不存在")
        File(files, name).takeIf(File::isFile) ?: throw BffException.notFound("文件缺失")
    }
    suspend fun reaction(owner: String, article: String): ArticleReaction = sql { c -> reaction(c, owner, article) }
    private fun reaction(c: Connection, owner: String, article: String) = ArticleReaction(
        c.query("SELECT COUNT(*) FROM reactions WHERE article=?", article) { it.getLong(1) }.first(),
        c.query("SELECT 1 FROM reactions WHERE article=? AND owner=?", article, owner) { true }.isNotEmpty())
    suspend fun setReaction(owner: String, article: String, liked: Boolean): ArticleReaction = sql { c ->
        c.transaction {
            if (liked) c.update("INSERT OR IGNORE INTO reactions(article,owner) VALUES(?,?)", article, owner)
            else c.update("DELETE FROM reactions WHERE article=? AND owner=?", article, owner)
        }
        reaction(c, owner, article)
    }
    suspend fun categories(owner: String): FeedCategories = sql { c -> FeedCategories(c.query("SELECT feed,category FROM feed_categories WHERE owner=?", owner) { it.getString(1) to it.getString(2) }.toMap()) }
    suspend fun setCategory(owner: String, feed: String, category: String) = sql { c ->
        c.update("INSERT INTO feed_categories(owner,feed,category) VALUES(?,?,?) ON CONFLICT(owner,feed) DO UPDATE SET category=excluded.category", owner, feed, category)
        Unit
    }
    suspend fun progress(owner: String, book: Long): ReadingProgress = sql { c ->
        c.query("SELECT * FROM progress WHERE owner=? AND book=?", owner, book) {
            ReadingProgress(book, it.getString("format"), it.getString("position"), it.getDouble("percent").takeUnless { _ -> it.wasNull() }, it.getString("updated"))
        }.firstOrNull() ?: ReadingProgress(book)
    }
    suspend fun setProgress(owner: String, book: Long, value: ReadingProgressUpdate) = sql { c ->
        val percent = value.percent
        if (percent != null && (!percent.isFinite() || percent !in 0.0..100.0)) throw BffException.badRequest("进度应为 0-100")
        c.update("INSERT INTO progress(owner,book,format,position,percent,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(owner,book) DO UPDATE SET format=excluded.format,position=excluded.position,percent=excluded.percent,updated=excluded.updated", owner, book, value.format, value.position, value.percent, java.time.Instant.now().toString())
        Unit
    }
    companion object { const val MAX_UPLOAD = 64 * 1024 * 1024 }
}
