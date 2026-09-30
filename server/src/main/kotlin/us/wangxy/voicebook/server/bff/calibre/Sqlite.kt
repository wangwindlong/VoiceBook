package us.wangxy.voicebook.server.bff.calibre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import us.wangxy.voicebook.server.bff.BffException

/** One short-lived connection per operation: both databases are owned by other processes. */
internal suspend fun <T> withSqlite(file: File, readOnly: Boolean, block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        if (!file.isFile) throw BffException.upstream("calibre", "找不到数据库 ${file.path}")
        openWithRetry(file, readOnly, block)
    }

private suspend fun <T> openWithRetry(file: File, readOnly: Boolean, block: (Connection) -> T): T {
    val config = SQLiteConfig().apply {
        setReadOnly(readOnly)
        busyTimeout = 5_000
    }
    var attempt = 0
    while (true) {
        try {
            return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties()).use(block)
        } catch (e: SQLException) {
            val busy = e.message?.let { "SQLITE_BUSY" in it || "locked" in it } == true
            if (!busy || ++attempt >= 3) throw e
            delay(200L * attempt)
        }
    }
}

internal fun Connection.transaction(block: () -> Unit) {
    autoCommit = false
    try {
        block()
        commit()
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = true
    }
}

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

internal fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { st ->
        args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeUpdate()
    }

internal fun Connection.tableExists(name: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", name) { true }.isNotEmpty()

internal fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
