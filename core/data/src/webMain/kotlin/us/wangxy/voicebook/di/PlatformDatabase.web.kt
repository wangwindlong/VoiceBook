package us.wangxy.voicebook.di

import org.koin.core.module.Module
import org.koin.dsl.module
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.db.LocalStorageLocalLibrary

actual fun platformDatabaseModule(): Module = module {
    single<LocalLibrary> { LocalStorageLocalLibrary() }
}
