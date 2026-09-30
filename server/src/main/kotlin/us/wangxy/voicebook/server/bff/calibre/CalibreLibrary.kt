package us.wangxy.voicebook.server.bff.calibre

import us.wangxy.voicebook.bff.contract.CalibreBook
import us.wangxy.voicebook.bff.contract.CalibreBookPage
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.CalibreConfig
import java.io.File
import java.sql.ResultSet

/** Read-only view of calibre's metadata.db plus the book files next to it. */
class CalibreLibrary(private val config: CalibreConfig) {
    val available: Boolean get() = config.metadataDb.isFile

    suspend fun books(query: String?, offset: Int, limit: Int, category: String? = null): CalibreBookPage =
        withSqlite(config.metadataDb, readOnly = true) { conn ->
            val pattern = query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + escapeLike(it) + "%" }
            val where = """
                WHERE (? IS NULL OR b.title LIKE ? ESCAPE '\' OR EXISTS (
                    SELECT 1 FROM books_authors_link l JOIN authors a ON a.id = l.author
                    WHERE l.book = b.id AND a.name LIKE ? ESCAPE '\'))
                AND (? IS NULL OR ? = '电子书' OR EXISTS (
                    SELECT 1 FROM books_tags_link l JOIN tags t ON t.id=l.tag WHERE l.book=b.id AND t.name=?))
            """.trimIndent()
            val total = conn.query("SELECT count(*) FROM books b $where", pattern, pattern, pattern, category, category, category) { it.getLong(1) }.first()
            val items = conn.query(
                "$SELECT_BOOK FROM books b $where ORDER BY b.timestamp DESC LIMIT ? OFFSET ?",
                pattern, pattern, pattern, category, category, category, limit, offset,
            ) { it.toBook(withDescription = false) }
            CalibreBookPage(items, total, offset, limit)
        }

    suspend fun book(id: Long): CalibreBook =
        withSqlite(config.metadataDb, readOnly = true) { conn ->
            conn.query("$SELECT_BOOK FROM books b WHERE b.id = ?", id) { it.toBook(withDescription = true) }.firstOrNull()
        } ?: throw BffException.notFound("书籍 $id 不存在")

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
