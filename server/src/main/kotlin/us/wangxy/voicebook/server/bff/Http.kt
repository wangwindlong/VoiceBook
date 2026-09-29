package us.wangxy.voicebook.server.bff

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

val BffJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Shared upstream client: never throws on non-2xx (callers map statuses), no redirects followed. */
fun upstreamHttpClient(engine: HttpClientEngine? = null): HttpClient {
    val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(OkHttp, config)
}

fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

fun hmacSha256Hex(key: String, value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal(value.toByteArray()).toHex()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
