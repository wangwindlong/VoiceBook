package us.wangxy.voicebook.voice.model

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelRepositoryTest {
    private val fs = SystemFileSystem
    private val weights = Random(7).nextBytes(600_000)
    private val vad = Random(8).nextBytes(300_000)
    private val archive = tarBz2(
        "sherpa-onnx-demo/model.int8.onnx" to weights,
        "sherpa-onnx-demo/tokens.txt" to "你 1\n好 2\n".encodeToByteArray(),
    )
    private val files = mapOf("/asr-models/demo.tar.bz2" to archive, "/asr-models/silero_vad.onnx" to vad)

    private val requests = CopyOnWriteArrayList<String>()
    private val dropFirstBody = AtomicBoolean(false)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/official") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        createContext("/mirror") { ex ->
            val path = ex.requestURI.path.removePrefix("/mirror")
            val range = ex.requestHeaders.getFirst("Range")
            requests += "$path ${range ?: ""}".trim()
            val body = files[path]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                if (from >= body.size) {
                    ex.sendResponseHeaders(416, -1)
                } else {
                    if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    ex.sendResponseHeaders(if (range != null) 206 else 200, (body.size - from).toLong())
                    if (dropFirstBody.compareAndSet(true, false)) {
                        ex.responseBody.write(body, from, (body.size - from) / 3) // connection drops mid-body
                    } else {
                        ex.responseBody.write(body, from, body.size - from)
                    }
                }
            }
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = HttpClient(OkHttp)

    @AfterTest
    fun tearDown() {
        server.stop(0)
        client.close()
    }

    private val demo = ModelArtifact(
        id = "stt_demo",
        name = "demo",
        source = ModelSource.TarBz2("asr-models/demo.tar.bz2"),
        downloadBytes = archive.size.toLong(),
        requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
    )
    private val silero = ModelArtifact(
        id = "vad_demo",
        name = "vad",
        source = ModelSource.SingleFile("asr-models/silero_vad.onnx"),
        downloadBytes = vad.size.toLong(),
        requiredFiles = listOf("silero_vad.onnx"),
    )

    private fun repository(root: Path) =
        ModelRepository(root, client, listOf("$base/official", "$base/mirror"), attempts = 2)

    @Test
    fun downloadsExtractsAndMarksReady() = runTest {
        val root = tempDir()
        val repo = repository(root)
        assertFalse(repo.isReady(demo))
        val progress = mutableListOf<ModelProgress>()
        repo.ensure(demo) { progress += it }
        repo.ensure(silero) {}

        assertTrue(repo.isReady(demo) && repo.isReady(silero))
        assertContentEquals(weights, read(Path(root, "stt_demo", "model.int8.onnx")))
        assertContentEquals(vad, read(Path(root, "vad_demo", "silero_vad.onnx")))
        assertFalse(fs.exists(Path(root, "stt_demo", "model.tar.bz2")), "archive is deleted after extraction")
        assertFalse(fs.exists(Path(root, "stt_demo", ".dl")))
        assertEquals("demo", read(Path(root, "stt_demo", ".source")).decodeToString())
        assertTrue(progress.any { it is ModelProgress.Downloading } && progress.any { it is ModelProgress.Extracting })

        requests.clear()
        repo.ensure(demo) {}
        assertTrue(requests.isEmpty(), "installed models are not fetched again")
    }

    @Test
    fun resumesInterruptedDownloadWithRange() = runTest {
        val root = tempDir()
        dropFirstBody.set(true)
        repository(root).ensure(silero) {}

        assertContentEquals(vad, read(Path(root, "vad_demo", "silero_vad.onnx")))
        assertEquals("/asr-models/silero_vad.onnx", requests[0])
        val resumedAt = requests[1].substringAfter("bytes=", "0").substringBefore('-').toInt()
        assertTrue(resumedAt > 0, "second request resumes the partial file: $requests")
    }

    @Test
    fun outdatedTagForcesReinstall() = runTest {
        val root = tempDir()
        val repo = repository(root)
        repo.ensure(silero) {}
        fs.sink(Path(root, "vad_demo", ".source")).buffered().use { it.writeString("old-version") }
        assertFalse(repo.isReady(silero))
        repo.ensure(silero) {}
        assertTrue(repo.isReady(silero))
    }

    @Test
    fun managerReportsProgressAndCallsOnReady() = runTest {
        val root = tempDir()
        var reloaded = 0
        val manager = ModelManager(repository(root), listOf(silero, demo), onReady = { reloaded++ })
        assertIs<ModelSetupState.Missing>(manager.state.value)
        manager.ensureModels()
        val final = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                manager.state.first { it is ModelSetupState.Ready || it is ModelSetupState.Failed }
            }
        }
        assertEquals(ModelSetupState.Ready, final)
        assertEquals(1, reloaded)
    }

    @Test
    fun managerSurfacesFailure() = runTest {
        val missing = silero.copy(id = "gone", source = ModelSource.SingleFile("asr-models/nope.onnx"))
        val manager = ModelManager(repository(tempDir()), listOf(missing))
        manager.ensureModels()
        val final = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                manager.state.first { it is ModelSetupState.Ready || it is ModelSetupState.Failed }
            }
        }
        assertIs<ModelSetupState.Failed>(final)
    }

    private fun tempDir() = Path(Files.createTempDirectory("models").toString())

    private fun read(path: Path): ByteArray = fs.source(path).buffered().use { it.readByteArray() }

    private fun tarBz2(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, data) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
