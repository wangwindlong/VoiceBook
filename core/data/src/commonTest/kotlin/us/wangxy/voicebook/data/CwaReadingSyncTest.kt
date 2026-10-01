package us.wangxy.voicebook.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import us.wangxy.voicebook.reader.api.*
import us.wangxy.voicebook.reader.store.*
import kotlin.test.*

class CwaReadingSyncTest {
    private val server = CalibreServer("https://cwa.test", "bob", "pw")
    private val bytes = "same downloaded book on both phones".encodeToByteArray()
    private fun initializer(library: LocalLibrary) = LibraryInitializer(object : ReaderStateStore {
        override fun load(): ReaderState? = null
        override fun save(state: ReaderState) {}
    }, library)

    @Test fun phoneAUploadIsRestoredOnPhoneBAndAfterRestart() = runTest {
        var remote = "{}"
        var writes = 0
        val engine = MockEngine { req ->
            assertTrue(req.url.encodedPath.startsWith("/kosync/"))
            if (req.method == HttpMethod.Put) {
                writes++
                val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                remote = buildJsonObject {
                    sent.forEach { (key, value) -> put(key, value) }
                    put("timestamp", 2001)
                    put("calibre_book_id", 7)
                    put("calibre_book_format", "EPUB")
                }.toString()
            }
            respond(remote, headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        fun client() = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val httpA = client(); val httpB = client(); val httpRestart = client()
        try {
            val libraryA = InMemoryLocalLibrary(); val libraryB = InMemoryLocalLibrary()
            val repoA = ReaderSessionRepository(libraryA, initializer(libraryA), calibre=CalibreWebApi(httpA))
            val repoB = ReaderSessionRepository(libraryB, initializer(libraryB), calibre=CalibreWebApi(httpB))
            repoA.saveServer(server); repoB.saveServer(server)
            libraryB.history.upsert(HistoryEntry(7,"Book",spineIndex=1,charOffset=5,progress=3,updatedAt=1_000_000))
            repoA.prepareDocument(server,7,"EPUB",bytes)
            repoA.recordProgress(HistoryEntry(7,"Book",spineIndex=2,charOffset=345,progress=10,updatedAt=2_000_000))
            assertEquals(1,writes)
            assertFalse(libraryA.history.get(7)!!.pendingSync)
            assertEquals(0.1,Json.parseToJsonElement(remote).jsonObject["percentage"]!!.jsonPrimitive.double)

            // First opening has no in-memory hash; after download/cache registration B must query again.
            assertEquals(3,repoB.localHistoryFor(7)!!.progress)
            repoB.prepareDocument(server,7,"EPUB",bytes)
            val restored = repoB.historyFor(7,"EPUB")!!
            assertEquals(10,restored.progress)
            assertEquals(2,restored.spineIndex)
            assertEquals(345,restored.charOffset)
            assertEquals(2_001_000,restored.updatedAt)

            libraryB.history.upsert(restored.copy(progress=3,updatedAt=1_000_000))
            val restarted = ReaderSessionRepository(libraryB,initializer(libraryB),calibre=CalibreWebApi(httpRestart))
            restarted.refreshHistory()
            assertEquals(10,libraryB.history.get(7)!!.progress)
            assertEquals(1,writes,"B must not upload its older location over A")
        } finally { httpA.close(); httpB.close(); httpRestart.close() }
    }

    @Test fun disabledUnmatchedAndHtmlResponsesKeepProgressPendingWithoutBookmarkFallback() = runTest {
        for (failure in listOf("disabled", "unmatched", "html", "cancel")) {
            var calls = 0
            val client = HttpClient(MockEngine { req ->
                calls++
                assertEquals("/kosync/syncs/progress",req.url.encodedPath)
                when (failure) {
                    "disabled" -> respond("""{"error":1000}""",HttpStatusCode.ServiceUnavailable)
                    "html" -> respond("<html>Login</html>")
                    "cancel" -> throw CancellationException("cancel upload")
                    else -> respond("""{"document":"${koreaderPartialMd5(bytes)}","timestamp":2001}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
                }
            }) { install(ContentNegotiation) { json() } }
            try {
                val library = InMemoryLocalLibrary()
                val repo = ReaderSessionRepository(library,initializer(library),calibre=CalibreWebApi(client))
                repo.saveServer(server); repo.prepareDocument(server,7,"EPUB",bytes)
                val entry = HistoryEntry(7,"Book",progress=10,updatedAt=2_000_000)
                if (failure == "cancel") assertFailsWith<CancellationException> { repo.recordProgress(entry) }
                else {
                    repo.recordProgress(entry)
                    assertNotNull(repo.progressSyncError.value)
                }
                assertTrue(library.history.get(7)!!.pendingSync)
                assertEquals(1,calls)
            } finally { client.close() }
        }
    }

    @Test fun newerOfflineProgressSurvivesOlderRemoteAndRetriesAfterRestart() = runTest {
        var uploaded = false
        val hash = koreaderPartialMd5(bytes)
        val client = HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Get) respond("""{"document":"$hash","progress":"1:10","percentage":0.03,"timestamp":1000,"calibre_book_id":7,"calibre_book_format":"EPUB"}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else {
                val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                assertEquals(0.10,body["percentage"]!!.jsonPrimitive.double)
                uploaded = true
                respond("""{"document":"$hash","timestamp":3000,"calibre_book_id":7}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }) { install(ContentNegotiation) { json() } }
        try {
            val library = InMemoryLocalLibrary()
            val repo = ReaderSessionRepository(library,initializer(library),calibre=CalibreWebApi(client))
            repo.saveServer(server); repo.prepareDocument(server,7,"EPUB",bytes)
            library.history.upsert(HistoryEntry(7,"Book",spineIndex=2,charOffset=40,progress=10,updatedAt=2_000_000,pendingSync=true))
            val restarted = ReaderSessionRepository(library,initializer(library),calibre=CalibreWebApi(client))
            val result = restarted.historyFor(7)!!
            assertTrue(uploaded)
            assertEquals(10,result.progress)
            assertFalse(result.pendingSync)
        } finally { client.close() }
    }
}
