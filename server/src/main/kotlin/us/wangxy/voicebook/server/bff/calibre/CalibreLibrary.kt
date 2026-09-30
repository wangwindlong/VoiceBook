package us.wangxy.voicebook.server.bff.calibre

import us.wangxy.voicebook.bff.contract.CalibreBook
import us.wangxy.voicebook.bff.contract.CalibreBookPage
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.CalibreConfig
import java.io.File
import java.sql.ResultSet
import java.sql.Connection
import us.wangxy.voicebook.bff.contract.CalibreTag

/** Read-only view of calibre's metadata.db plus the book files next to it. */
class CalibreLibrary(private val config: CalibreConfig) {
    @Volatile private var versionCache: Pair<String,String>? = null
    val available: Boolean get() = config.metadataDb.isFile

    suspend fun books(query: String?, offset: Int, limit: Int, category: String? = null,
                      tags: List<String> = emptyList(), tagMode: String = "any", sort: String = "added"): CalibreBookPage =
        withSqlite(config.metadataDb, readOnly = true) { conn ->
            val pattern = query?.trim()?.takeIf(String::isNotEmpty)?.let { "%${escapeLike(it)}%" }
            val args = mutableListOf<Any?>(pattern, pattern, pattern)
            val where = StringBuilder("""WHERE (? IS NULL OR b.title LIKE ? ESCAPE '\' OR EXISTS (
                SELECT 1 FROM books_authors_link l JOIN authors a ON a.id=l.author
                WHERE l.book=b.id AND a.name LIKE ? ESCAPE '\'))""")
            if (category != null) {
                where.append(" AND EXISTS (SELECT 1 FROM books_tags_link l JOIN tags t ON t.id=l.tag WHERE l.book=b.id AND t.name=?)")
                args.add(category)
            }
            if (tags.isNotEmpty()) {
                val marks = tags.joinToString(",") { "?" }
                where.append(" AND (SELECT count(DISTINCT t.name) FROM books_tags_link l JOIN tags t ON t.id=l.tag WHERE l.book=b.id AND t.name IN ($marks)) >= ?")
                args.addAll(tags.distinct()); args.add(if (tagMode == "all") tags.distinct().size else 1)
            }
            val ordering = when (sort) {
                "title" -> "b.sort COLLATE NOCASE ASC"
                "author" -> "b.author_sort COLLATE NOCASE ASC"
                "pubdate" -> "b.pubdate DESC"
                "rating" -> "rating DESC"
                else -> "b.timestamp DESC"
            }
            val total = conn.query("SELECT count(*) FROM books b $where", *args.toTypedArray()) { it.getLong(1) }.first()
            val items = conn.query("${conn.selectBook()} FROM books b $where ORDER BY $ordering, b.id DESC LIMIT ? OFFSET ?",
                *(args + listOf(limit, offset)).toTypedArray()) { it.toBook(false) }
            CalibreBookPage(items, total, offset, limit, conn.libraryVersion())
        }

    suspend fun tags(): List<CalibreTag> = withSqlite(config.metadataDb, true) { conn ->
        conn.query("SELECT t.name, count(*) AS c FROM tags t JOIN books_tags_link l ON l.tag=t.id GROUP BY t.id ORDER BY c DESC,t.name") {
            CalibreTag(it.getString(1), it.getInt(2))
        }
    }

    suspend fun book(id: Long): CalibreBook = withSqlite(config.metadataDb, true) { conn ->
        conn.query("${conn.selectBook()} FROM books b WHERE b.id=?", id) { it.toBook(true) }.firstOrNull()
    } ?: throw BffException.notFound("书籍 $id 不存在")

    private fun Connection.libraryVersion(): String {
        // Include names and relationships: counts alone miss a tag rename/reassignment.
        val stamp=listOf(config.metadataDb,File(config.metadataDb.path+"-wal")).joinToString { file ->
            if(file.exists()) "${java.nio.file.Files.getLastModifiedTime(file.toPath())}:${file.length()}" else "missing"
        }
        versionCache?.takeIf { it.first==stamp }?.let { return it.second }
        val custom=query("SELECT name FROM sqlite_master WHERE type='table' AND (name GLOB 'custom_column_[0-9]*' OR name GLOB 'books_custom_column_[0-9]*_link')") { it.getString(1) }
            .filter { it.matches(Regex("(custom_column_[0-9]+|books_custom_column_[0-9]+_link)")) }
        val parts = (custom + listOf("books", "tags", "books_tags_link", "publishers", "books_publishers_link", "identifiers", "comments", "ratings", "books_ratings_link", "languages", "books_languages_link", "books_pages_link", "custom_columns"))
            .filter { tableExists(it) }.flatMap { table ->
                query("SELECT * FROM $table ORDER BY 1") { rs ->
                    (1..rs.metaData.columnCount).joinToString("\u001f") { rs.getString(it).orEmpty() }
                }
            }
        val version = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u001e").toByteArray()).joinToString("") { "%02x".format(it) }
        versionCache=stamp to version
        return version
    }

    private fun Connection.selectBook(): String {
        fun optional(table: String, sql: String, alias: String) = if (tableExists(table)) "($sql) AS $alias" else "NULL AS $alias"
        val editionId = if (tableExists("custom_columns")) query("SELECT id FROM custom_columns WHERE label='edition' AND datatype='text'") { it.getLong(1) }.firstOrNull() else null
        val editionTable = editionId?.let { "custom_column_$it" }
        val editionLink = editionId?.let { "books_custom_column_${it}_link" }
        val edition = when {
            editionTable != null && editionLink != null && tableExists(editionTable) && tableExists(editionLink) -> "(SELECT c.value FROM $editionLink l JOIN $editionTable c ON c.id=l.value WHERE l.book=b.id LIMIT 1)"
            editionTable != null && tableExists(editionTable) -> "(SELECT value FROM $editionTable WHERE book=b.id LIMIT 1)"
            else -> "NULL"
        }
        val modified = query("PRAGMA table_info(books)") { it.getString("name") }.contains("last_modified")
        return SELECT_BOOK + ", " + listOf(
            optional("publishers", "SELECT p.name FROM books_publishers_link l JOIN publishers p ON p.id=l.publisher WHERE l.book=b.id", "publisher"),
            optional("languages", "SELECT group_concat(g.lang_code,char(31)) FROM books_languages_link l JOIN languages g ON g.id=l.lang_code WHERE l.book=b.id", "languages"),
            optional("identifiers", "SELECT group_concat(i.type || ':' || i.val,char(31)) FROM identifiers i WHERE i.book=b.id", "identifiers"),
            optional("ratings", "SELECT r.rating FROM books_ratings_link l JOIN ratings r ON r.id=l.rating WHERE l.book=b.id", "rating"),
            optional("books_pages_link", "SELECT pages FROM books_pages_link WHERE book=b.id", "pages"),
            "$edition AS edition", (if (modified) "b.last_modified" else "NULL") + " AS last_modified"
        ).joinToString(", ")
    }

    suspend fun coverFile(id: Long): File {
        val dir = bookDir(id)
        return File(dir, "cover.jpg").takeIf { it.isFile } ?: throw BffException.notFound("书籍 $id 没有封面")
    }

    suspend fun bookFile(id: Long, format: String): File {
        val fmt = format.uppercase()
        val name = withSqlite(config.metadataDb, readOnly = true) { conn ->
            conn.query("SELECT name FROM data WHERE book = ? AND upper(format) = ?", id, fmt) { it.getString(1) }.firstOrNull()
        } ?: throw BffException.notFound("书籍 $id 没有 $fmt 格式")
        val file = File(bookDir(id), "$name.${fmt.lowercase()}")
        return file.takeIf { it.isFile } ?: throw BffException.notFound("书籍文件缺失")
    }

    private suspend fun bookDir(id: Long): File {
        val path = withSqlite(config.metadataDb, readOnly = true) { conn ->
            conn.query("SELECT path FROM books WHERE id = ?", id) { it.getString(1) }.firstOrNull()
        } ?: throw BffException.notFound("书籍 $id 不存在")
        val root = config.libraryDir.canonicalFile
        val dir = File(root, path).canonicalFile
        if (!dir.toPath().startsWith(root.toPath())) throw BffException.notFound("书籍 $id 路径非法")
        return dir
    }

    private fun ResultSet.toBook(withDescription: Boolean) = CalibreBook(
        id = getLong("id"),
        title = getString("title"),
        authors = getString("authors").splitList(),
        authorSort = getString("author_sort"),
        tags = getString("tags").splitList(),
        series = getString("series"),
        seriesIndex = doubleOrNull("series_index").takeIf { getString("series") != null },
        pubdate = getString("pubdate"),
        addedAt = getString("timestamp"),
        hasCover = getInt("has_cover") != 0,
        formats = getString("formats").splitList().map { it.uppercase() },
        description = if (withDescription) getString("description") else null,
        publisher = getString("publisher"), languages = getString("languages").splitList(),
        identifiers = getString("identifiers").splitList().mapNotNull { value ->
            value.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap(),
        rating = intOrNull("rating")?.takeIf { it > 0 }?.div(2.0),
        pageCount = intOrNull("pages")?.takeIf { it > 0 },
        edition = getString("edition"), lastModified = getString("last_modified"),
    )

    private fun String?.splitList(): List<String> = this?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val SEP = '\u001f'

        val SELECT_BOOK = """
            SELECT b.id, b.title, b.author_sort, b.pubdate, b.timestamp, b.series_index, b.has_cover,
              (SELECT group_concat(a.name, char(31)) FROM books_authors_link l JOIN authors a ON a.id = l.author WHERE l.book = b.id) AS authors,
              (SELECT group_concat(t.name, char(31)) FROM books_tags_link l JOIN tags t ON t.id = l.tag WHERE l.book = b.id) AS tags,
              (SELECT s.name FROM books_series_link l JOIN series s ON s.id = l.series WHERE l.book = b.id) AS series,
              (SELECT group_concat(d.format, char(31)) FROM data d WHERE d.book = b.id) AS formats,
              (SELECT c.text FROM comments c WHERE c.book = b.id) AS description
        """.trimIndent()
    }
}
