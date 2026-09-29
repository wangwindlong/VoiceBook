package us.wangxy.voicebook.server.bff.calibre

import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.bff.contract.ReadingProgress
import us.wangxy.voicebook.bff.contract.ReadingProgressUpdate
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.CalibreConfig
import java.sql.Connection
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reading progress in calibre-web-automated's app.db, which CWA writes concurrently:
 * every write is one short transaction touching only `bookmark` (web reader position) and
 * `kobo_reading_state` / `kobo_bookmark` (percentage, shared with Kobo sync).
 */
class CalibreProgressStore(private val config: CalibreConfig) {
    private val log = LoggerFactory.getLogger(CalibreProgressStore::class.java)

    suspend fun userExists(username: String): Boolean =
        withSqlite(config.appDb, readOnly = true) { it.userId(username) != null }

    suspend fun read(username: String, bookId: Long): ReadingProgress =
        withSqlite(config.appDb, readOnly = true) { conn ->
            val userId = conn.requireUser(username)
            val bookmark = conn.query(
                "SELECT format, bookmark_key FROM bookmark WHERE user_id = ? AND book_id = ? ORDER BY id DESC LIMIT 1",
                userId, bookId,
            ) { it.getString(1) to it.getString(2) }.firstOrNull()
            val kobo = if (conn.tableExists("kobo_bookmark")) {
                conn.query(
                    """
                    SELECT kb.progress_percent, coalesce(kb.last_modified, krs.last_modified) AS updated
                    FROM kobo_reading_state krs LEFT JOIN kobo_bookmark kb ON kb.kobo_reading_state_id = krs.id
                    WHERE krs.user_id = ? AND krs.book_id = ? ORDER BY krs.id DESC LIMIT 1
                    """.trimIndent(),
                    userId, bookId,
                ) { it.doubleOrNull("progress_percent") to it.getString("updated") }.firstOrNull()
            } else null
            ReadingProgress(
                bookId = bookId,
                format = bookmark?.first,
                position = bookmark?.second,
                percent = kobo?.first,
                updatedAt = kobo?.second,
            )
        }

    suspend fun write(username: String, bookId: Long, update: ReadingProgressUpdate) {
        val format = update.format.uppercase()
        withSqlite(config.appDb, readOnly = false) { conn ->
            val userId = conn.requireUser(username)
            conn.transaction {
                val updated = conn.update(
                    "UPDATE bookmark SET bookmark_key = ? WHERE user_id = ? AND book_id = ? AND format = ?",
                    update.position, userId, bookId, format,
                )
                if (updated == 0) {
                    conn.update(
                        "INSERT INTO bookmark (user_id, book_id, format, bookmark_key) VALUES (?, ?, ?, ?)",
                        userId, bookId, format, update.position,
                    )
                }
                update.percent?.let { percent ->
                    runCatching { conn.writeKoboPercent(userId, bookId, percent.coerceIn(0.0, 100.0)) }
                        .onFailure { log.warn("kobo progress write skipped for book {}: {}", bookId, it.message) }
                }
            }
        }
    }

    private fun Connection.writeKoboPercent(userId: Long, bookId: Long, percent: Double) {
        if (!tableExists("kobo_reading_state") || !tableExists("kobo_bookmark")) return
        val now = LocalDateTime.now(ZoneOffset.UTC).format(SQLALCHEMY_TS)
        val stateId = query(
            "SELECT id FROM kobo_reading_state WHERE user_id = ? AND book_id = ? ORDER BY id DESC LIMIT 1",
            userId, bookId,
        ) { it.getLong(1) }.firstOrNull() ?: run {
            update(
                "INSERT INTO kobo_reading_state (user_id, book_id, last_modified, priority_timestamp) VALUES (?, ?, ?, ?)",
                userId, bookId, now, now,
            )
            query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
        }
        update("UPDATE kobo_reading_state SET last_modified = ?, priority_timestamp = ? WHERE id = ?", now, now, stateId)
        val touched = update(
            "UPDATE kobo_bookmark SET progress_percent = ?, last_modified = ? WHERE kobo_reading_state_id = ?",
            percent, now, stateId,
        )
        if (touched == 0) {
            update(
                "INSERT INTO kobo_bookmark (kobo_reading_state_id, last_modified, progress_percent) VALUES (?, ?, ?)",
                stateId, now, percent,
            )
        }
    }

    private fun Connection.userId(username: String): Long? =
        query("SELECT id FROM user WHERE lower(name) = lower(?)", username) { it.getLong(1) }.firstOrNull()

    private fun Connection.requireUser(username: String): Long = userId(username)
        ?: throw BffException(
            HttpStatusCode.Conflict,
            CALIBRE_USER_NOT_PROVISIONED,
            "calibre 账号尚未初始化，请调用 /api/calibre/activate 并提供密码",
        )

    companion object {
        const val CALIBRE_USER_NOT_PROVISIONED = "CALIBRE_USER_NOT_PROVISIONED"
        private val SQLALCHEMY_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
    }
}
