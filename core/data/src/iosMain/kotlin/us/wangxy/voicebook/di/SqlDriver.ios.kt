package us.wangxy.voicebook.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import org.koin.core.Koin
import us.wangxy.voicebook.db.VoiceBookDatabase

internal actual fun createSqlDriver(koin: Koin): SqlDriver =
    NativeSqliteDriver(
        DatabaseConfiguration(
            name = "voicebook.db",
            version = VoiceBookDatabase.Schema.version.toInt(),
            create = { connection ->
                wrapConnection(connection) { VoiceBookDatabase.Schema.create(it) }
            },
            upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) {
                    VoiceBookDatabase.Schema.migrate(it, oldVersion.toLong(), newVersion.toLong())
                }
            },
        ),
    )
