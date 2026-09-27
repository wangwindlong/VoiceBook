package us.wangxy.voicebook.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.Koin
import us.wangxy.voicebook.db.VoiceBookDatabase
import java.io.File
import java.util.Properties

internal actual fun createSqlDriver(koin: Koin): SqlDriver {
    val dir = File(System.getProperty("user.home"), ".voicebook").apply { mkdirs() }
    val dbFile = File(dir, "voicebook.db")
    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbFile.absolutePath}",
        properties = Properties(),
        schema = VoiceBookDatabase.Schema,
    )
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return driver
}
