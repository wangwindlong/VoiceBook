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
                s.execute("CREATE TABLE IF NOT EXISTS reading_events (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, book INTEGER NOT NULL, kind TEXT NOT NULL, position TEXT, percent REAL, seconds INTEGER, device TEXT, ts TEXT NOT NULL)")
                s.execute("CREATE INDEX IF NOT EXISTS idx_reading_events_owner ON reading_events(owner,ts DESC)")
                s.execute("CREATE INDEX IF NOT EXISTS idx_reading_events_book ON reading_events(owner,book,ts DESC)")
                s.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, kind TEXT NOT NULL, object_type TEXT NOT NULL, object_id TEXT NOT NULL, value REAL, source TEXT NOT NULL, ts TEXT NOT NULL, UNIQUE(owner,kind,object_type,object_id,ts))")
                s.execute("CREATE INDEX IF NOT EXISTS idx_events_owner_ts ON events(owner,ts DESC)")
                s.execute("CREATE TABLE IF NOT EXISTS interest (owner TEXT NOT NULL, tag TEXT NOT NULL, weight REAL NOT NULL DEFAULT 0, source TEXT NOT NULL, updated_at TEXT NOT NULL, evidence INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,tag))")
                s.execute("CREATE TABLE IF NOT EXISTS content_links (owner TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(owner,key))")
                s.execute("CREATE TABLE IF NOT EXISTS profile_revision (owner TEXT PRIMARY KEY, event_id INTEGER NOT NULL)")

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
        val now = java.time.Instant.now().toString()
        c.transaction {
            val previous = c.query("SELECT percent FROM progress WHERE owner=? AND book=?", owner, book) { it.getDouble(1) }.firstOrNull()
            c.update("INSERT INTO progress(owner,book,format,position,percent,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(owner,book) DO UPDATE SET format=excluded.format,position=excluded.position,percent=excluded.percent,updated=excluded.updated", owner, book, value.format, value.position, value.percent, now)
            val kind = if (percent != null && percent >= 99 && (previous == null || previous < 99)) "finish" else if (previous == null) "open" else "progress"
            c.update("INSERT INTO reading_events(owner,book,kind,position,percent,ts) VALUES(?,?,?,?,?,?)", owner, book, kind, value.position, percent, now)
            if (kind != "progress") insertEvent(c, owner, BehaviorEvent(if (kind == "finish") "finish_book" else "open_book", "book", book.toString(), percent, now), "server")
        }
        Unit
    }

    suspend fun history(owner: String, limit: Int): ReadingHistoryPage = sql { c ->
        val items = c.query("""SELECT p.*,
            (SELECT coalesce(sum(seconds),0) FROM reading_events e WHERE e.owner=p.owner AND e.book=p.book) AS seconds,
            (SELECT count(*) FROM reading_events e WHERE e.owner=p.owner AND e.book=p.book AND e.kind='open') AS sessions
            FROM progress p WHERE p.owner=? ORDER BY p.updated DESC LIMIT ?""", owner, limit) {
            ReadingHistoryItem(it.getLong("book"), format=it.getString("format"), position=it.getString("position"),
                percent=it.getDouble("percent").takeUnless { _ -> it.wasNull() }, updatedAt=it.getString("updated"),
                totalSeconds=it.getLong("seconds"), sessions=it.getInt("sessions"))
        }
        ReadingHistoryPage(items, c.query("SELECT count(*) FROM progress WHERE owner=?", owner) { it.getInt(1) }.first())
    }

    suspend fun record(owner: String, event: BehaviorEvent, source: String = "server") = record(owner, listOf(event), source)
    suspend fun record(owner: String, events: List<BehaviorEvent>, source: String) = sql { c ->
        c.transaction {
            events.forEach { event ->
                if (insertEvent(c, owner, event, source) > 0 && event.objectType == "book" && event.kind in setOf("open_book", "finish_book", "reading_session")) {
                    c.update("INSERT INTO reading_events(owner,book,kind,seconds,device,ts) VALUES(?,?,?,?,?,?)",
                        owner, event.objectId.toLong(), if (event.kind == "open_book") "open" else if (event.kind == "finish_book") "finish" else "progress", event.seconds, event.device, event.ts)
                }
            }
        }
        Unit
    }
    private fun insertEvent(c: Connection, owner: String, event: BehaviorEvent, source: String): Int =
        c.update("INSERT OR IGNORE INTO events(owner,kind,object_type,object_id,value,source,ts) VALUES(?,?,?,?,?,?,?)",
            owner, event.kind, event.objectType, event.objectId, event.value, source, event.ts)

    suspend fun rememberLinks(owner: String, links: Map<String,String>) = sql { c ->
        c.transaction { links.forEach { (key,value) -> c.update("INSERT INTO content_links VALUES(?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value",owner,key,value) } }; Unit
    }
    suspend fun resolveComment(owner: String, id: String): Pair<String,String>? = sql { c ->
        fun lookup(key: String)=c.query("SELECT value FROM content_links WHERE owner=? AND key=?",owner,key) { it.getString(1) }.firstOrNull()
        val page=if(id.startsWith('/')) id else lookup("comment:$id") ?: return@sql null
        when {
            page.startsWith("/calibre/book/") -> "book" to page.substringAfterLast('/')
            page.startsWith("/rss/article/") -> lookup("article:${page.substringAfterLast('/')}")?.let { "entry" to it }
            else -> null
        }
    }
    data class StoredEvent(val id: Long, val event: BehaviorEvent)
    suspend fun profileOwners(): List<String> = sql { c -> c.query("SELECT DISTINCT owner FROM interest UNION SELECT DISTINCT owner FROM events") { it.getString(1) } }
    suspend fun dirtyOwners(): List<String> = sql { c ->
        c.query("SELECT e.owner FROM events e LEFT JOIN profile_revision r ON r.owner=e.owner GROUP BY e.owner HAVING max(e.id)>coalesce(max(r.event_id),0)") { it.getString(1) }
    }
    suspend fun events(owner: String): List<StoredEvent> = sql { c ->
        c.query("SELECT * FROM events WHERE owner=? ORDER BY id", owner) {
            StoredEvent(it.getLong("id"), BehaviorEvent(it.getString("kind"),it.getString("object_type"),it.getString("object_id"),it.getDouble("value").takeUnless { _ -> it.wasNull() },it.getString("ts")))
        }
    }
    suspend fun profile(owner: String): InterestProfile = sql { c ->
        InterestProfile(c.query("SELECT * FROM interest WHERE owner=? ORDER BY weight DESC,tag", owner) {
            InterestTag(it.getString("tag"),it.getDouble("weight"),it.getString("source"),it.getInt("evidence"),it.getInt("muted") != 0)
        })
    }
    suspend fun replaceProfile(owner: String, tags: List<InterestTag>, revision: Long) = sql { c ->
        c.transaction {
            c.update("DELETE FROM interest WHERE owner=? AND source!='manual' AND muted=0", owner)
            tags.forEach { tag ->
                c.update("INSERT INTO interest(owner,tag,weight,source,updated_at,evidence) VALUES(?,?,?,?,?,?) ON CONFLICT(owner,tag) DO UPDATE SET weight=excluded.weight,evidence=excluded.evidence,updated_at=excluded.updated_at WHERE interest.muted=0 AND interest.source!='manual'",
                    owner,tag.tag,tag.weight,tag.source,java.time.Instant.now().toString(),tag.evidence)
            }
            c.update("INSERT INTO profile_revision VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET event_id=excluded.event_id", owner,revision)
        }; Unit
    }
    suspend fun selectInterests(owner: String, tags: List<String>) = sql { c ->
        c.transaction { tags.forEach { tag ->
            c.update("INSERT INTO interest(owner,tag,weight,source,updated_at) VALUES(?,?,0.8,'manual',?) ON CONFLICT(owner,tag) DO UPDATE SET weight=0.8,source='manual',muted=0,updated_at=excluded.updated_at", owner,normalizeTag(tag),java.time.Instant.now().toString())
        } }; Unit
    }
    suspend fun mute(owner: String, tag: String) = sql { c ->
        c.update("INSERT INTO interest(owner,tag,weight,source,updated_at,muted) VALUES(?,?,0,'manual',?,1) ON CONFLICT(owner,tag) DO UPDATE SET weight=0,muted=1",owner,normalizeTag(tag),java.time.Instant.now().toString()); Unit
    }
    fun normalizeTag(tag: String) = tag.lowercase().filterNot(Char::isWhitespace)
    companion object { const val MAX_UPLOAD = 64 * 1024 * 1024 }
}
