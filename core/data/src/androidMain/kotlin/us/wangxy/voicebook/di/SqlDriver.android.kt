package us.wangxy.voicebook.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.koin.core.Koin
import us.wangxy.voicebook.db.VoiceBookDatabase

internal actual fun createSqlDriver(koin: Koin): SqlDriver =
    AndroidSqliteDriver(
        schema = VoiceBookDatabase.Schema,
        context = koin.get(),
        name = "voicebook.db",
        factory = RequerySQLiteOpenHelperFactory(),
    )
