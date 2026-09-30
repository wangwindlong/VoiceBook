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

/** Best-effort percentage mirror. App positions must never enter CWA's EPUB.js bookmarks. */
class CwaProgressMirror(private val config: CalibreConfig) {
    private val log = LoggerFactory.getLogger(CwaProgressMirror::class.java)
    suspend fun userExists(username: String): Boolean =
        withSqlite(config.appDb, true) { it.userId(username) != null }

    suspend fun write(username: String, bookId: Long, percent: Double?) {
        if (bookId <= 0 || percent == null || !percent.isFinite() || percent !in 0.0..100.0) return
        try {
            withSqlite(config.appDb, false) { conn ->
                val userId = conn.userId(username) ?: return@withSqlite
                conn.transaction { conn.writeKoboPercent(userId, bookId, percent) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log.warn("CWA progress mirror failed for book {}", bookId, e) }
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

    companion object {
        private val SQLALCHEMY_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
    }
}
