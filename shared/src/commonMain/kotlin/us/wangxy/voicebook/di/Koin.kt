package us.wangxy.voicebook.di

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.data.InMemoryMuseumStorage
import us.wangxy.voicebook.data.KtorMuseumApi
import us.wangxy.voicebook.data.MuseumApi
import us.wangxy.voicebook.data.MuseumRepository
import us.wangxy.voicebook.data.MuseumStorage
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.ReaderSessionRepository
import us.wangxy.voicebook.audio.di.audioModule
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.data.createBookBytesCache
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.reader.store.createReaderStateStore
import us.wangxy.voicebook.screens.detail.DetailViewModel
import us.wangxy.voicebook.screens.library.LibraryViewModel
import us.wangxy.voicebook.screens.list.ListViewModel
import us.wangxy.voicebook.screens.mine.MineViewModel
import us.wangxy.voicebook.screens.reader.ReaderViewModel
import us.wangxy.voicebook.screens.shelf.ShelfViewModel
import us.wangxy.voicebook.screens.voice.VoiceViewModel
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.createThemeStore
import us.wangxy.voicebook.ui.UiPrefsController
import us.wangxy.voicebook.ui.createUiPrefsStore
import us.wangxy.voicebook.voice.di.VoiceConfig
import us.wangxy.voicebook.voice.di.voiceModule

val infrastructureModule = module {
    includes(networkModule)
    single { createReaderStateStore() }
    single { createBookBytesCache() }
    singleOf(::LibraryInitializer)
    singleOf(::BookRepository)
    singleOf(::ReaderSessionRepository)
}

val dataModule = module {
    single<MuseumApi> { KtorMuseumApi(get()) }
    single<MuseumStorage> { InMemoryMuseumStorage() }
    single {
        MuseumRepository(get(), get()).apply {
            initialize()
        }
    }
}

val readerModule = module {
    single { ReaderStateController(get()) }
    // The reader reuses the shared HttpClient; OPDS/EPUB bytes don't need JSON negotiation.
    single { CalibreWebApi(get()) }
    factoryOf(::LibraryViewModel)
    factoryOf(::ReaderViewModel)
    factoryOf(::ShelfViewModel)
}

val themeModule = module {
    single { ThemeController(createThemeStore()) }
    single { SeedColorState() }
    single { SeedColorExtractor(get(), get(), get(), get()) }
    single { UiPrefsController(createUiPrefsStore()) }
}

val viewModelModule = module {
    factoryOf(::ListViewModel)
    factoryOf(::DetailViewModel)
    factoryOf(::MineViewModel)
    factory { VoiceViewModel(get(), get(), get(), get(), getOrNull()) }
}

fun initKoin(
    voiceConfig: VoiceConfig = VoiceConfig(),
    platformModules: List<Module> = emptyList(),
) {
    startKoin {
        modules(platformModules)
        modules(
            platformDatabaseModule(),
            infrastructureModule,
            dataModule,
            themeModule,
            audioModule,
            rssModule,
            readerModule,
            voiceModule(voiceConfig),
            viewModelModule,
        )
    }
    CrashReporter.install()
}
