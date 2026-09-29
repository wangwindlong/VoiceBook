package us.wangxy.voicebook.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import us.wangxy.voicebook.bff.BffAuthApi

/** Shared HttpClient (JSON negotiation) + per-platform engines wired in this module. */
val networkModule = module {
    single {
        HttpClient {
            install(ContentNegotiation) {
                // calibre-web serves application/xml for OPDS feeds; JSON negotiation covers the rest.
                json(Json { ignoreUnknownKeys = true }, contentType = ContentType.Any)
            }
        }
    }
    single { BffAuthApi(get()) }
}
