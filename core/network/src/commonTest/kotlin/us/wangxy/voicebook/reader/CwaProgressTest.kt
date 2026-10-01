package us.wangxy.voicebook.reader

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import us.wangxy.voicebook.reader.api.*
import kotlin.test.*

class CwaProgressTest {
    @Test fun checksumMatchesCwaSamplingAcrossPlatforms() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc".encodeToByteArray()))
        // Reference computed using Python hashlib and CWA's 0, 1024, 4096, 16384... offsets.
        val bytes = ByteArray(70000) { ((it * 17 + it / 251) % 256).toByte() }
        assertEquals("449c840b35da4cf4a8323cac73085ab0", koreaderPartialMd5(bytes))
    }

    @Test fun sendsRealJsonAndReadsTimestamp() = runTest {
        val hash = koreaderPartialMd5("book".encodeToByteArray())
        val client = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Put, req.method)
            assertEquals("/library/kosync/syncs/progress", req.url.encodedPath)
            assertEquals("Basic Ym9iOnB3", req.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            assertEquals(hash, body["document"]!!.jsonPrimitive.content)
            assertEquals(0.10, body["percentage"]!!.jsonPrimitive.double)
            assertEquals("2:345", body["progress"]!!.jsonPrimitive.content)
            respond("""{"document":"$hash","timestamp":1234,"calibre_book_id":7}""", headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val ack = CalibreWebApi(client).saveKoreaderProgress(CalibreServer("https://cwa.test/library", "bob", "pw"),hash,"2:345",10)
            assertEquals(1234L, ack.timestamp)
            assertEquals(7, ack.calibre_book_id)
        } finally { client.close() }
    }

    @Test fun htmlLoginAndDisabledSyncAreNotSuccessfulWrites() = runTest {
        for ((body, status) in listOf("<html>login</html>" to HttpStatusCode.OK, "{}" to HttpStatusCode.OK,
            """{"error":1000,"message":"KOReader sync is disabled"}""" to HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { respond(body,status) }) { install(ContentNegotiation) { json() } }
            try {
                assertFailsWith<CalibreWebApiException> {
                    CalibreWebApi(client).saveKoreaderProgress(CalibreServer("https://cwa.test","bob","pw"),"a".repeat(32),"1:0",10)
                }
            } finally { client.close() }
        }
    }
}
