package us.wangxy.voicebook.server.bff.miniflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import us.wangxy.voicebook.server.bff.calibre.query
import us.wangxy.voicebook.server.bff.calibre.transaction
import us.wangxy.voicebook.server.bff.calibre.update
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Per-user view state over the shared Miniflux account. */
data class EntryState(val read: Boolean, val starred: Boolean)

/**
 * The BFF's own SQLite database. Miniflux holds every feed/entry exactly once under the shared
 * account; who subscribed to which feed and who read/starred which entry lives here.
 *
 * An entry without a row is unread and not starred.
 */
class UserRssStore(private val file: File) {
    init {
        file.absoluteFile.parentFile?.mkdirs()
        open { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS user_feed (" +
                        "username TEXT NOT NULL, feed_id INTEGER NOT NULL, PRIMARY KEY (username, feed_id))",
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS user_entry (" +
                        "username TEXT NOT NULL, entry_id INTEGER NOT NULL, " +
                        "is_read INTEGER NOT NULL DEFAULT 0, starred INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (username, entry_id))",
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_user_entry_flags ON user_entry (username, is_read, starred)")
            }
        }
    }

    private fun <T> open(block: (Connection) -> T): T {
        val config = SQLiteConfig().apply { busyTimeout = 5_000 }
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties()).use(block)
    }

    private suspend fun <T> io(block: (Connection) -> T): T = withContext(Dispatchers.IO) { open(block) }

    suspend fun subscribedFeeds(username: String): Set<Long> = io { c ->
        c.query("SELECT feed_id FROM user_feed WHERE username = ?", username) { it.getLong(1) }.toSet()
    }

    suspend fun isSubscribed(username: String, feedId: Long): Boolean = io { c ->
        c.query("SELECT 1 FROM user_feed WHERE username = ? AND feed_id = ?", username, feedId) { true }.isNotEmpty()
    }

    suspend fun subscribe(username: String, feedId: Long) {
        io { c -> c.update("INSERT OR IGNORE INTO user_feed (username, feed_id) VALUES (?, ?)", username, feedId) }
    }

    /** Returns how many users still follow [feedId] afterwards. */
    suspend fun unsubscribe(username: String, feedId: Long): Int = io { c ->
        c.update("DELETE FROM user_feed WHERE username = ? AND feed_id = ?", username, feedId)
        c.query("SELECT COUNT(*) FROM user_feed WHERE feed_id = ?", feedId) { it.getInt(1) }.first()
    }

    suspend fun states(username: String, entryIds: Collection<Long>): Map<Long, EntryState> {
        if (entryIds.isEmpty()) return emptyMap()
        return io { c ->
            entryIds.chunked(500).flatMap { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                c.query(
                    "SELECT entry_id, is_read, starred FROM user_entry WHERE username = ? AND entry_id IN ($marks)",
                    username, *chunk.toTypedArray(),
                ) { Triple(it.getLong(1), it.getInt(2) != 0, it.getInt(3) != 0) }
            }.associate { (id, read, starred) -> id to EntryState(read, starred) }
        }
    }

    suspend fun setRead(username: String, entryIds: Collection<Long>, read: Boolean) {
        if (entryIds.isEmpty()) return
        val flag = if (read) 1 else 0
        io { c ->
            c.transaction {
                entryIds.forEach { id ->
                    c.update(
                        "INSERT INTO user_entry (username, entry_id, is_read) VALUES (?, ?, ?) " +
                            "ON CONFLICT (username, entry_id) DO UPDATE SET is_read = excluded.is_read",
                        username, id, flag,
                    )
                }
            }
        }
    }

    /** Flips the star and returns the new value. */
    suspend fun toggleStarred(username: String, entryId: Long): Boolean = io { c ->
        var result = false
        c.transaction {
            val current = c.query(
                "SELECT starred FROM user_entry WHERE username = ? AND entry_id = ?", username, entryId,
            ) { it.getInt(1) != 0 }.firstOrNull() ?: false
            result = !current
            c.update(
                "INSERT INTO user_entry (username, entry_id, starred) VALUES (?, ?, ?) " +
                    "ON CONFLICT (username, entry_id) DO UPDATE SET starred = excluded.starred",
                username, entryId, if (result) 1 else 0,
            )
        }
        result
    }
}
