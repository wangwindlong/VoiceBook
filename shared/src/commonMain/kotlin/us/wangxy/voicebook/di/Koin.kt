package us.wangxy.voicebook.di

import us.wangxy.voicebook.data.InMemoryMuseumStorage
import us.wangxy.voicebook.data.KtorMuseumApi
import us.wangxy.voicebook.data.MuseumApi
import us.wangxy.voicebook.data.MuseumRepository
import us.wangxy.voicebook.data.MuseumStorage
import us.wangxy.voicebook.screens.detail.DetailViewModel
import us.wangxy.voicebook.screens.list.ListViewModel
import us.wangxy.voicebook.screens.voice.VoiceViewModel
import us.wangxy.voicebook.theme.ThemeController
import us.wangxy.voicebook.theme.createThemeStore
import us.wangxy.voicebook.voice.di.VoiceConfig
import us.wangxy.voicebook.voice.di.voiceModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val dataModule = module {
    single {
        val json = Json { ignoreUnknownKeys = true }
        HttpClient {
            install(ContentNegotiation) {
                // TODO Fix API so it serves application/json
                json(json, contentType = ContentType.Any)
            }
        }
    }

    single<MuseumApi> { KtorMuseumApi(get()) }
    single<MuseumStorage> { InMemoryMuseumStorage() }
    single {
        MuseumRepository(get(), get()).apply {
            initialize()
        }
    }
}

val themeModule = module {
    single { ThemeController(createThemeStore()) }
}

val viewModelModule = module {
    factoryOf(::ListViewModel)
    factoryOf(::DetailViewModel)
    factory { VoiceViewModel(get(), get(), get(), get(), getOrNull()) }
}

fun initKoin(
    voiceConfig: VoiceConfig = VoiceConfig(),
    platformModules: List<Module> = emptyList(),
) {
    startKoin {
        modules(platformModules)
        modules(
            dataModule,
            themeModule,
            voiceModule(voiceConfig),
            viewModelModule,
        )
    }
}
