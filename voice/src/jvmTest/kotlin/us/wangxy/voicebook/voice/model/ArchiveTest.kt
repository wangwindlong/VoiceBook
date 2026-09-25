package us.wangxy.voicebook.voice.model

import us.wangxy.voicebook.voice.model.archive.BZip2Source
import us.wangxy.voicebook.voice.model.archive.TarExtractor
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BZip2SourceTest {
    private fun bzip2(data: ByteArray, blockSize: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out, blockSize).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decode(compressed: ByteArray): ByteArray {
        val input = Buffer().apply { write(compressed) }
        return BZip2Source(input).buffered().use { it.readByteArray() }
    }

    private fun assertRoundTrip(data: ByteArray, blockSize: Int = 1) =
        assertContentEquals(data, decode(bzip2(data, blockSize)))

    @Test
    fun emptyAndTinyInputs() {
        assertRoundTrip(ByteArray(0))
        assertRoundTrip(byteArrayOf(42))
        assertRoundTrip("hello, bzip2".encodeToByteArray())
    }

    @Test
    fun incompressibleDataSpanningManyBlocks() {
        assertRoundTrip(Random(1).nextBytes(450_000)) // 100k blocks -> 5 blocks
        assertRoundTrip(Random(2).nextBytes(1_200_000), blockSize = 9)
    }

    @Test
    fun runLengthEdgeCases() {
        val out = ByteArrayOutputStream()
        // Runs around the RLE1 thresholds (4 literal + count byte up to 251) and RUNA/RUNB runs.
        for (len in listOf(1, 3, 4, 5, 8, 254, 255, 256, 259, 260, 1000, 70_000)) {
            repeat(len) { out.write('a'.code + len % 26) }
            out.write('#'.code)
        }
        repeat(300_000) { out.write(0) }
        assertRoundTrip(out.toByteArray())
    }

    @Test
    fun structuredText() {
        val text = buildString { repeat(40_000) { append("第$it 行：全双工语音播报与识别 voice line $it\n") } }
        assertRoundTrip(text.encodeToByteArray())
    }

    @Test
    fun concatenatedStreams() {
        val a = Random(3).nextBytes(150_000)
        val b = "second stream".encodeToByteArray()
        assertContentEquals(a + b, decode(bzip2(a) + bzip2(b)))
    }

    @Test
    fun corruptionIsDetected() {
        val compressed = bzip2(Random(4).nextBytes(50_000))
        compressed[compressed.size / 2] = (compressed[compressed.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<Exception> { decode(compressed) }
        assertFailsWith<IOException> { decode(compressed.copyOf(compressed.size / 3)) }
        assertFailsWith<IOException> { decode("not bzip2".encodeToByteArray()) }
    }
}

class TarExtractorTest {
    private val fs = SystemFileSystem

    private fun tarBz2(entries: List<Pair<String, ByteArray?>>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, data) in entries) {
                val entry = TarArchiveEntry(name)
                if (data != null) entry.size = data.size.toLong()
                tar.putArchiveEntry(entry)
                data?.let(tar::write)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun extractsStrippingTopLevelDirectory() {
        val longName = "model-root/dict/" + "x".repeat(150) + "/jieba.dict.utf8"
        val big = Random(5).nextBytes(700_000)
        val archive = tarBz2(
            listOf(
                "model-root/" to null,
                "model-root/tokens.txt" to "a 1\nb 2\n".encodeToByteArray(),
                "model-root/model.int8.onnx" to big,
                longName to "dict".encodeToByteArray(),
                "model-root/._tokens.txt" to "apple double".encodeToByteArray(),
                "model-root/empty.txt" to ByteArray(0),
            ),
        )
        val dir = Path(Files.createTempDirectory("tar").toString())
        val input = Buffer().apply { write(archive) }
        BZip2Source(input).buffered().use { TarExtractor(fs).extract(it, dir) }

        assertEquals("a 1\nb 2\n", read(Path(dir, "tokens.txt")).decodeToString())
        assertContentEquals(big, read(Path(dir, "model.int8.onnx")))
        assertEquals("dict", read(Path(dir, longName.removePrefix("model-root/"))).decodeToString())
        assertFalse(fs.exists(Path(dir, "._tokens.txt")))
        assertTrue(fs.exists(Path(dir, "empty.txt")))
    }

    @Test
    fun rejectsPathTraversal() {
        val archive = tarBz2(listOf("root/../../evil.txt" to "x".encodeToByteArray()))
        val dir = Path(Files.createTempDirectory("tar").toString())
        assertFailsWith<IOException> {
            BZip2Source(Buffer().apply { write(archive) }).buffered().use { TarExtractor(fs).extract(it, dir) }
        }
    }

    private fun read(path: Path): ByteArray = fs.source(path).buffered().use { it.readByteArray() }
}
