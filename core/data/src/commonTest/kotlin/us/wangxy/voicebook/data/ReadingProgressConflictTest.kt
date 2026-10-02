@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.wangxy.voicebook.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.ContentApi
import us.wangxy.voicebook.reader.store.HistoryEntry
import us.wangxy.voicebook.reader.store.ReaderState
import us.wangxy.voicebook.reader.store.ReaderStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingProgressConflictTest {
    private class Session : BffSession {
        override val signedInUser = MutableStateFlow<String?>("alice")
        override fun baseUrl() = "https://bff.test"
        override suspend fun accessToken() = "token"
        override fun currentAccessToken() = "token"
    }

    private class Harness {
        val library = InMemoryLocalLibrary()
        val session = Session()
        var offline = false
        var position = "1:10"
        var percent = 10
        var puts = 0
        var blockPut: CompletableDeferred<Unit>? = null
        val putStarted = CompletableDeferred<Unit>()
        var clock = 1_790_900_000_000L
        val client = HttpClient(MockEngine { request ->
            if (offline) throw IllegalStateException("offline")
            if (request.method == HttpMethod.Put) {
                puts++
                blockPut?.let { gate ->
                    blockPut = null
                    putStarted.complete(Unit)
                    gate.await()
                }
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                position = body.getValue("position").jsonPrimitive.content
                percent = body.getValue("percent").jsonPrimitive.content.toDouble().toInt()
                respond("", HttpStatusCode.NoContent)
            } else respond(
                """{"bookId":7,"format":"EPUB","position":"$position","percent":$percent,"updatedAt":"2026-10-01T00:00:00Z"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json() } }
        fun repo() = ReaderSessionRepository(library, LibraryInitializer(object : ReaderStateStore {
            override fun load(): ReaderState? = null
            override fun save(state: ReaderState) {}
        }, library), session, ContentApi(client, session), nowMillis = { clock })
        fun local() = HistoryEntry(7, "Book", spineIndex = 2, charOffset = 20, progress = 20, updatedAt = ++clock)
    }

    @Test fun unchangedCloudAllowsOfflineRetryWithPersistedBaseline() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            h.offline = true
            repo.recordProgress(h.local())
            assertTrue(h.library.history.get(7, "alice")!!.pendingSync)
            h.offline = false
            val restarted = h.repo()
            restarted.retryPendingProgress()
            assertEquals(1, h.puts)
            assertEquals("2:20", h.position)
            assertFalse(h.library.history.get(7, "alice")!!.pendingSync)
            assertTrue(restarted.progressConflicts.value.isEmpty())
        } finally { h.client.close() }
    }

    @Test fun remoteLeadSuggestsJumpAndReadingContinues() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            h.offline = true
            repo.recordProgress(h.local())
            h.position = "3:30"; h.percent = 30; h.offline = false
            repo.retryPendingProgress()
            val conflict = repo.progressConflicts.value.single()
            assertEquals(20, conflict.local.progress)
            assertEquals(30, conflict.cloud.progress)
            assertEquals(0, h.puts)
            repo.recordProgress(h.local().copy(progress = 21))
            assertEquals(21, h.library.history.get(7, "alice")!!.progress)
            // Jump restores the offered anchor without waiting for another network request.
            h.position = "4:40"; h.percent = 40
            repo.resolveConflict(conflict, useCloud = true)
            assertEquals(30, h.library.history.get(7, "alice")!!.progress)
            assertFalse(h.library.history.get(7, "alice")!!.pendingSync)
            assertTrue(repo.progressConflicts.value.isEmpty())
            assertEquals(0, h.puts)
        } finally { h.client.close() }
    }

    @Test fun localChoiceUploadsAndAccountSwitchCannotResolveOldConflict() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            h.offline = true; repo.recordProgress(h.local())
            h.offline = false; h.position = "3:30"; h.percent = 30
            repo.retryPendingProgress()
            val conflict = repo.progressConflicts.value.single()
            h.session.signedInUser.value = "bob"
            repo.resolveConflict(conflict, useCloud = false)
            assertEquals(0, h.puts)
            assertNull(h.library.history.get(7, "bob"))
            h.session.signedInUser.value = "alice"
            repo.resolveConflict(conflict, useCloud = false)
            assertEquals(1, h.puts)
            assertEquals("2:20", h.position)
            assertFalse(h.library.history.get(7, "alice")!!.pendingSync)
        } finally { h.client.close() }
    }

    @Test fun localEditDuringUploadDoesNotBecomeAFalseConflict() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            val gate = CompletableDeferred<Unit>()
            h.blockPut = gate
            val first = async { repo.recordProgress(h.local()) }
            h.putStarted.await()
            val second = async { repo.recordProgress(h.local().copy(spineIndex = 3, charOffset = 30, progress = 30)) }
            runCurrent()
            assertEquals(30, h.library.history.get(7, "alice")!!.progress)
            gate.complete(Unit)
            first.await(); second.await()
            assertEquals(2, h.puts)
            assertEquals("3:30", h.position)
            assertFalse(h.library.history.get(7, "alice")!!.pendingSync)
            assertTrue(repo.progressConflicts.value.isEmpty())
        } finally { h.client.close() }
    }

    @Test fun listeningPositionIsIndependentAndLateCallbacksCannotCrossAccounts() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            val writer = repo.listeningProgressWriter()
            writer(h.local())
            assertEquals(10, h.library.history.get(7, "alice")!!.progress)
            assertEquals(20, repo.listeningHistoryFor(7)!!.progress)
            assertEquals(0, h.puts)
            h.session.signedInUser.value = "bob"
            writer(h.local().copy(progress = 80))
            assertNull(repo.listeningHistoryFor(7))
            h.session.signedInUser.value = "alice"
            assertEquals(20, repo.listeningHistoryFor(7)!!.progress)
        } finally { h.client.close() }
    }

    @Test fun unrecognizedCloudPositionCannotBeOverwrittenByPendingLocalEdit() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            repo.historyFor(7)
            h.offline = true; repo.recordProgress(h.local())
            h.offline = false; h.position = "epubcfi(/6/4!/4/2)"
            repo.retryPendingProgress()
            assertEquals(0, h.puts)
            assertTrue(h.library.history.get(7, "alice")!!.pendingSync)
            assertNotNull(repo.progressSyncError.value)
        } finally { h.client.close() }
    }

    @Test fun localLeadContinuesEvenWhenLocalClockIsAhead() = runTest {
        val h = Harness()
        try {
            h.library.history.upsert(h.local().copy(updatedAt = Long.MAX_VALUE), "alice")
            h.repo().historyFor(7)
            assertEquals(20, h.library.history.get(7, "alice")!!.progress)
            assertEquals(1, h.puts)
            assertTrue(h.repo().progressConflicts.value.isEmpty())
        } finally { h.client.close() }
    }
    @Test fun tinyRemoteLeadDoesNotMoveLocalAnchorOrPrompt() = runTest {
        val h = Harness()
        try {
            h.library.history.upsert(h.local(), "alice")
            h.position = "2:25"; h.percent = 21
            val repo = h.repo()
            repo.historyFor(7)
            assertEquals(20, h.library.history.get(7, "alice")!!.progress)
            assertEquals(20, h.library.history.get(7, "alice")!!.charOffset)
            assertTrue(repo.progressConflicts.value.isEmpty())
            assertEquals(0, h.puts)
        } finally { h.client.close() }
    }

    @Test fun transientFailuresStaySilentUntilProlonged() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            h.offline = true
            repo.recordProgress(h.local())
            assertNull(repo.progressSyncError.value)
            h.clock += 300_000
            repo.retryPendingProgress()
            assertNotNull(repo.progressSyncError.value)
            h.offline = false
            repo.retryPendingProgress()
            assertNull(repo.progressSyncError.value)
        } finally { h.client.close() }
    }

    @Test fun firstOpeningOffersCloudAnchorWithoutMovingVisibleBeginning() = runTest {
        val h = Harness()
        try {
            val repo = h.repo()
            val local = repo.historyFor(7, "EPUB", HistoryEntry(7, "Book"))!!
            assertEquals(0, local.progress)
            assertEquals(10, repo.progressConflicts.value.single().cloud.progress)
            assertEquals(0, h.puts)
        } finally { h.client.close() }
    }

}
