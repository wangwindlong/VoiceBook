package us.wangxy.voicebook.di

import app.cash.sqldelight.db.SqlDriver
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.db.SqlDelightLocalLibrary
import us.wangxy.voicebook.db.VoiceBookDatabase

/** Platform database files need different drivers; each leaf source set supplies one. */
internal expect fun createSqlDriver(koin: Koin): SqlDriver

actual fun platformDatabaseModule(): Module = module {
    single { VoiceBookDatabase(createSqlDriver(getKoin())) }
    single<LocalLibrary> { SqlDelightLocalLibrary(get()) }
}
