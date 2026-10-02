package us.wangxy.voicebook.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.bff.*
import us.wangxy.voicebook.reader.store.*
import kotlin.test.*

class ReadingSyncTest {
    private class Session : BffSession {
        override val signedInUser=MutableStateFlow<String?>("alice")
        override fun baseUrl()="https://bff.test"
        override suspend fun accessToken()=signedInUser.value?.let { "token-$it" }
        override fun currentAccessToken()=signedInUser.value?.let { "token-$it" }
    }
    private fun initializer(library: LocalLibrary)=LibraryInitializer(object: ReaderStateStore {
        override fun load(): ReaderState?=null
        override fun save(state: ReaderState) {}
    },library)

    @Test fun cachesAndHistoryAreIsolatedIncludingNegativeIds()=runTest {
        val library=InMemoryLocalLibrary()
        val alice=CachedBook(-1,"Alice book","","","",0)
        val bob=alice.copy(title="Bob book")
        library.bookCache.upsertAll(listOf(alice),"alice")
        library.bookCache.upsertAll(listOf(bob),"bob")
        library.history.upsert(HistoryEntry(-1,"Alice",progress=42),"alice")
        library.history.upsert(HistoryEntry(-1,"Bob",progress=17),"bob")
        assertEquals("Alice book",library.bookCache.page(20,0,"alice").single().title)
        assertEquals("Bob book",library.bookCache.page(20,0,"bob").single().title)
        assertEquals(0,library.bookCache.count())
        assertEquals(42,library.history.get(-1,"alice")?.progress)
        assertEquals(17,library.history.get(-1,"bob")?.progress)
        library.bookCache.clear("alice")
        assertEquals(1,library.bookCache.count("bob"))
    }

    @Test fun throttlesRetriesAndNeverUploadsForSwitchedAccount()=runTest {
        val library=InMemoryLocalLibrary(); val session=Session()
        var now=100_000L; var offline=false; var puts=0
        val client=HttpClient(MockEngine { request ->
            if(offline) throw IllegalStateException("offline")
            if(request.method==HttpMethod.Put) { puts++; respond("",HttpStatusCode.NoContent) }
            else respond("""{"bookId":1}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo=ReaderSessionRepository(library,initializer(library),session,ContentApi(client,session)) { now }
            fun entry(progress: Int)=HistoryEntry(1,"Book",progress=progress,updatedAt=now)
            repo.recordProgress(entry(10)); assertEquals(1,puts)
            now+=1000; repo.recordProgress(entry(10)); assertEquals(1,puts)
            assertTrue(library.history.get(1,"alice")!!.pendingSync)
            now+=1000; repo.recordProgress(entry(11)); assertEquals(2,puts)
            now+=15_000; repo.recordProgress(entry(11)); assertEquals(3,puts)
            offline=true; now+=15_000; repo.recordProgress(entry(12))
            assertTrue(library.history.get(1,"alice")!!.pendingSync)
            offline=false; repo.historyFor(1); assertEquals(4,puts)
            assertFalse(library.history.get(1,"alice")!!.pendingSync)
            session.signedInUser.value="bob"
            repo.recordProgress(entry(90),account="alice")
            assertEquals(4,puts); assertNull(library.history.get(1,"bob"))
        } finally { client.close() }
    }

    @Test fun incompatibleServerHistoryPreservesLocalAndDisablingPersonalizationStopsEvents()=runTest {
        val library=InMemoryLocalLibrary(); val session=Session(); var posts=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) { posts++; respond("",HttpStatusCode.NoContent) }
            else respond("""{"items":[{"bookId":1,"title":"Remote","format":"PDF","position":"8:0","percent":40,"updatedAt":"2026-09-30T00:00:00Z"}],"total":1}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo=ReaderSessionRepository(library,initializer(library),session,ContentApi(client,session))
            library.history.upsert(HistoryEntry(1,"Local",updatedAt=1),"alice")
            repo.refreshHistory()
            assertEquals("EPUB",library.history.get(1,"alice")?.format)
            assertNotNull(repo.progressSyncError.value)
            assertEquals(0,library.history.get(1,"alice")?.spineIndex)
            assertNull(library.history.get(1,"bob"))
            library.settings.put("personalization.enabled","false")
            repo.event("open_book",1); assertEquals(0,posts)
            library.settings.put("personalization.enabled","true")
            repo.event("open_book",1); assertEquals(1,posts)
        } finally { client.close() }
    }
}
