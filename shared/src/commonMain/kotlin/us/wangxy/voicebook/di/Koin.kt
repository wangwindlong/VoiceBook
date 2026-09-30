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
import us.wangxy.voicebook.bff.ArtalkApi
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.data.createBookBytesCache
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.reader.store.createReaderStateStore
import us.wangxy.voicebook.screens.detail.DetailViewModel
import us.wangxy.voicebook.screens.library.LibraryViewModel
import us.wangxy.voicebook.screens.list.ListViewModel
import us.wangxy.voicebook.screens.mine.MineViewModel
import us.wangxy.voicebook.screens.reader.CommentExtrasProvider
import us.wangxy.voicebook.screens.reader.MockCommentExtrasProvider
import us.wangxy.voicebook.screens.reader.ReaderViewModel
import us.wangxy.voicebook.screens.reader.ReaderCommentsViewModel
import us.wangxy.voicebook.screens.shelf.ShelfViewModel
import us.wangxy.voicebook.screens.voice.VoiceViewModel
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.theme.SeedColorState
import us.wangxy.voicebook.theme.createThemeStore
import us.wangxy.voicebook.ui.UiPrefsController
import us.wangxy.voicebook.ui.createUiPrefsStore
import us.wangxy.voicebook.auth.AuthController
import us.wangxy.voicebook.auth.createAuthStore
import us.wangxy.voicebook.screens.auth.AuthViewModel
import us.wangxy.voicebook.voice.di.VoiceConfig
import us.wangxy.voicebook.voice.di.voiceModule
import us.wangxy.voicebook.audio.AudioPlayer as RssAudioPlayer
import us.wangxy.voicebook.listen.ListenController
import us.wangxy.voicebook.voice.audio.AudioPlayer as VoiceAudioPlayer
import us.wangxy.voicebook.voice.di.MediaAudioPlayer
import us.wangxy.voicebook.voice.listen.MediaPlaybackHost
import us.wangxy.voicebook.voice.listen.NoMediaPlaybackHost
import us.wangxy.voicebook.voice.listen.VoiceCommandRecognizer

val infrastructureModule = module {
    includes(networkModule)
    single { createReaderStateStore() }
    single { createBookBytesCache() }
    singleOf(::LibraryInitializer)
    // Both resolve the effective calibre backend: the BFF while signed in, else the configured server.
    single { BookRepository(get(), get(), get(), get<BffSession>()) }
    single { ReaderSessionRepository(get(), get(), get<BffSession>(), get(), calibre = get()) }
    single { us.wangxy.voicebook.data.PersonalizationRepository(get(), get(), get()) }
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
    single { CalibreWebApi(get(), get<BffSession>()) }
    // 评论列表的进度/时长/笔记目前是假数据；真实数据就绪后换掉这一个绑定即可
    single<CommentExtrasProvider> { MockCommentExtrasProvider() }
    factoryOf(::LibraryViewModel)
    factoryOf(::ReaderViewModel)
    factoryOf(::ReaderCommentsViewModel)
    factoryOf(::ShelfViewModel)
    // 听书会话是 app 级单例：离开阅读页继续播，由通知栏/迷你条控制。
    single {
        val rss = get<RssAudioPlayer>()
        val repository = get<ReaderSessionRepository>()
        ListenController(
            synthesizer = inject(),
            player = getOrNull<VoiceAudioPlayer>(MediaAudioPlayer) ?: get<VoiceAudioPlayer>(),
            models = inject(),
            saveProgress = repository::recordProgress,
            host = getOrNull<MediaPlaybackHost>() ?: NoMediaPlaybackHost,
            commands = lazy { VoiceCommandRecognizer(get(), get()) },
            beforePlay = rss::stop,
        )
    }
}

val themeModule = module {
    single { ThemeController(createThemeStore()) }
    single { SeedColorState() }
    single { SeedColorExtractor(get(), get(), get(), get(), get<BffSession>()) }
    single { UiPrefsController(createUiPrefsStore()) }
}

val authModule = module {
    single { AuthController(get(), createAuthStore()) }
    // Lets modules that may not depend on :feature:auth (e.g. :feature:rss) reach the BFF.
    single<BffSession> { get<AuthController>() }
    single { ArtalkApi(get(), get()) }
    factoryOf(::AuthViewModel)
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
    debugNetworkLogging: Boolean = false,
) {
    startKoin {
        properties(mapOf("debugNetworkLogging" to debugNetworkLogging))
        modules(platformModules)
        modules(
            platformDatabaseModule(),
            infrastructureModule,
            dataModule,
            themeModule,
            audioModule,
            authModule,
            rssModule,
            readerModule,
            voiceModule(voiceConfig) { installDebugNetworkLogging(debugNetworkLogging) },
            viewModelModule,
        )
    }
    CrashReporter.install()
}
