package us.wangxy.voicebook.server.bff.profile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.server.bff.content.ContentStore
import us.wangxy.voicebook.server.bff.calibre.CalibreLibrary
import us.wangxy.voicebook.server.bff.createCalibreFixture
import us.wangxy.voicebook.server.bff.config.MinifluxConfig
import us.wangxy.voicebook.server.bff.miniflux.*
import kotlin.test.*

class ProfileServiceTest {
    @Test fun decayNormalizationManualChoicesMuteAndIsolation() = runTest {
        val root=Files.createTempDirectory("profile-test").toFile()
        val store=ContentStore(File(root,"content.db"),File(root,"books"))
        val http=HttpClient(MockEngine { respond("[]",headers=headersOf(HttpHeaders.ContentType,"application/json")) })
        val rss=SharedRssService(MinifluxGateway(http,MinifluxConfig("http://mf","k","s".repeat(32))),UserRssStore(File(root,"rss.db")))
        val service=ProfileService(store,CalibreLibrary(createCalibreFixture()),rss)
        val now=Instant.parse("2026-09-30T00:00:00Z")
        try {
            store.record("alice",BehaviorEvent("open_book","book","1",ts=now.minusSeconds(30*86400L).toString()))
            store.record("alice",BehaviorEvent("open_book","book","2",ts=now.toString()))
            service.rebuild("alice",now)
            val profile=service.profile("alice").tags.associateBy { it.tag }
            assertEquals(1.0,profile.getValue("科幻").weight,0.000001)
            assertEquals(1.0/3,profile.getValue("技术").weight,0.000001)
            assertEquals(2,profile.getValue("科幻").evidence)
            assertTrue(service.profile("bob").tags.isEmpty())
            service.select("alice",listOf("manual"))
            service.mute("alice"," 技术 ")
            store.record("alice",BehaviorEvent("finish_book","book","1",ts=now.plusSeconds(1).toString()))
            service.rebuild("alice",now.plusSeconds(1))
            val after=service.profile("alice").tags.associateBy { it.tag }
            assertEquals(0.8,after.getValue("manual").weight)
            assertEquals(0.0,after.getValue("技术").weight)
            assertTrue(after.getValue("技术").muted)
            assertTrue(store.dirtyOwners().isEmpty())
        } finally { http.close(); root.deleteRecursively() }
    }
}
