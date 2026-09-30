package us.wangxy.voicebook.di

import co.touchlab.kermit.Logger as AppLogger
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging

/** Installs full HTTP logging only for debug clients; release clients have no logging plugin. */
fun HttpClientConfig<*>.installDebugNetworkLogging(
    enabled: Boolean,
    writeLog: (String) -> Unit = { AppLogger.withTag("KtorHttp").d(it) },
) {
    if (!enabled) return
    install(Logging) {
        level = LogLevel.ALL
        logger = object : Logger {
            override fun log(message: String) {
                // Keep each Android Logcat entry below its byte limit, including UTF-8 text.
                message.lineSequence().forEach { line ->
                    line.chunked(900).forEach(writeLog)
                }
            }
        }
    }
}
