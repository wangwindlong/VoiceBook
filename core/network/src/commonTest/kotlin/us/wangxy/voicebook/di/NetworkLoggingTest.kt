package us.wangxy.voicebook.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkLoggingTest {
    @Test
    fun debugLogsRequestAndErrorResponseWithoutConsumingBody() = runTest {
        val messages = mutableListOf<String>()
        val responseLogged = CompletableDeferred<Unit>()
        val responseBody = "response-" + "长".repeat(2000)
        val client = HttpClient(MockEngine {
            respond(responseBody, HttpStatusCode.BadRequest, headersOf("Content-Type", "text/plain"))
        }) {
            installDebugNetworkLogging(true) { message ->
                messages += message
                if (message.contains("BODY END") && messages.any { it.contains("response-") }) {
                    responseLogged.complete(Unit)
                }
            }
        }
        try {
            val response = client.post("https://example.test/api?q=search") {
                header("X-Test-Header", "header-value")
                setBody("request-body")
            }
            assertEquals(responseBody, response.bodyAsText())
            withTimeout(5_000) { responseLogged.await() }
            val log = messages.joinToString("\n")
            listOf("q=search", "X-Test-Header", "header-value", "request-body", "400", "response-").forEach {
                assertTrue(log.contains(it), "Missing log value: $it")
            }
            assertEquals(2000, log.count { it == '长' })
            assertTrue(messages.all { it.length <= 900 })
        } finally {
            client.close()
        }
    }

    @Test
    fun releaseDoesNotInstallLogging() = runTest {
        val messages = mutableListOf<String>()
        val client = HttpClient(MockEngine { respond("ok") }) {
            installDebugNetworkLogging(false, messages::add)
        }
        try {
            assertNull(client.pluginOrNull(Logging))
            assertEquals("ok", client.post("https://example.test").bodyAsText())
            assertTrue(messages.isEmpty())
        } finally {
            client.close()
        }
    }
}
