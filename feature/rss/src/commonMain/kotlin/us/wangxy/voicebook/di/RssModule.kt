package us.wangxy.voicebook.di

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module
import us.wangxy.voicebook.data.LibraryInitializer
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.data.theme.SeedColorExtractor
import us.wangxy.voicebook.rss.LocalSyncCoordinator
import us.wangxy.voicebook.rss.FeedTextFetcher
import us.wangxy.voicebook.rss.MinifluxApi
import us.wangxy.voicebook.rss.RssFeedFetcher
import us.wangxy.voicebook.rss.MinifluxSyncCoordinator
import us.wangxy.voicebook.rss.RssRepository
import us.wangxy.voicebook.screens.rss.FeedsViewModel
import us.wangxy.voicebook.screens.rss.RssViewModel
import io.ktor.client.HttpClient

val rssModule = module {
    single<FeedTextFetcher> { RssFeedFetcher(get()) }
    single { LocalSyncCoordinator(get(), get(), get()) }
    single { MinifluxSyncCoordinator(get(), get(), get()) }
    single {
        RssRepository(
            client = get(),
            library = get(),
            initializer = get(),
            extractor = get(),
            localSync = get(),
            minifluxSync = get(),
            minifluxApi = get(),
        )
    }
    factory { MinifluxApi(get()) }
    factoryOf(::RssViewModel)
    factoryOf(::FeedsViewModel)
}
