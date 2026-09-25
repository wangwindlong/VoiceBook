package us.wangxy.voicebook.voice.model

import us.wangxy.voicebook.voice.model.archive.BZip2Source
import us.wangxy.voicebook.voice.model.archive.TarExtractor
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

sealed interface ModelProgress {
    /** [total] is null when the server doesn't report a length. */
    data class Downloading(val bytes: Long, val total: Long?) : ModelProgress

    data class Extracting(val fraction: Float) : ModelProgress
}

/**
 * Downloads and installs [ModelArtifact]s under [root].
 *
 * `.dl` exists from the first byte until the artifact is fully installed; while it is present the
 * artifact is not ready and a partial download is resumed with an HTTP Range request. Each base
 * URL is tried in order, [attempts] rounds in total. A corrupt archive is deleted so the next
 * attempt starts from scratch.
 */
class ModelRepository(
    private val root: Path,
    private val client: HttpClient,
    private val baseUrls: List<String>,
    private val fs: FileSystem = SystemFileSystem,
    private val attempts: Int = 3,
) {
    fun dirOf(artifact: ModelArtifact): Path = Path(root, artifact.id)

    fun isReady(artifact: ModelArtifact): Boolean {
        val dir = dirOf(artifact)
        if (fs.exists(Path(dir, DownloadMarker))) return false
        val complete = artifact.requiredFiles.all { name ->
            val meta = fs.metadataOrNull(Path(dir, name))
            meta != null && meta.isRegularFile && meta.size > 0
        }
        if (!complete) return false
        // No tag: installed by hand or by an older build; accept it.
        val tag = readTag(artifact) ?: return true
        return tag == artifact.sourceTag
    }

    suspend fun ensure(artifact: ModelArtifact, onProgress: (ModelProgress) -> Unit) {
        if (isReady(artifact)) return
        val dir = dirOf(artifact)
        fs.createDirectories(dir)
        val marker = Path(dir, DownloadMarker)
        val resuming = fs.exists(marker)
        if (!resuming) fs.sink(marker).close()

        when (val source = artifact.source) {
            is ModelSource.SingleFile -> {
                val dest = Path(dir, artifact.requiredFiles.first())
                if (!resuming) deleteIfExists(dest)
                fetch(source.path, dest, artifact.downloadBytes, onProgress)
            }
            is ModelSource.TarBz2 -> {
                val archive = Path(dir, ArchiveName)
                if (!resuming) deleteIfExists(archive)
                fetch(source.path, archive, artifact.downloadBytes, onProgress)
                try {
                    extract(archive, dir, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    deleteIfExists(archive)
                    throw IOException("Extracting ${artifact.id} failed: ${e.message}", e)
                }
                deleteIfExists(archive)
            }
        }
        val missing = artifact.requiredFiles.filterNot { fs.exists(Path(dir, it)) }
        if (missing.isNotEmpty()) throw IOException("${artifact.id} is missing $missing after download")
        fs.sink(Path(dir, SourceTag)).buffered().use { it.writeString(artifact.sourceTag) }
        fs.delete(marker)
    }

    private suspend fun fetch(path: String, dest: Path, expectedBytes: Long, onProgress: (ModelProgress) -> Unit) {
        var lastError: Exception? = null
        repeat(attempts) {
            for (base in baseUrls) {
                try {
                    download("${base.trimEnd('/')}/$path", dest, onProgress)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw IOException("Download of $path (~${expectedBytes / (1024 * 1024)} MB) failed: ${lastError?.message}", lastError)
    }

    private suspend fun download(url: String, dest: Path, onProgress: (ModelProgress) -> Unit) {
        val offset = fs.metadataOrNull(dest)?.size ?: 0L
        client.prepareGet(url) {
            if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response ->
            val status = response.status
            if (status == HttpStatusCode.RequestedRangeNotSatisfiable && offset > 0) {
                onProgress(ModelProgress.Downloading(offset, offset))
                return@execute
            }
            if (!status.isSuccess()) throw IOException("HTTP ${status.value} for $url")
            val append = status == HttpStatusCode.PartialContent
            val length = response.contentLength()
            val total = if (append) {
                response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                    ?: length?.let { it + offset }
            } else {
                length
            }
            var done = if (append) offset else 0L
            var reported = done
            onProgress(ModelProgress.Downloading(done, total))
            val channel = response.bodyAsChannel()
            fs.sink(dest, append).buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    done += n
                    if (done - reported >= ProgressStep) {
                        reported = done
                        onProgress(ModelProgress.Downloading(done, total))
                    }
                }
            }
            if (total != null && done != total) throw IOException("Incomplete download: $done of $total bytes")
            onProgress(ModelProgress.Downloading(done, total ?: done))
        }
    }

    private suspend fun extract(archive: Path, dir: Path, onProgress: (ModelProgress) -> Unit) {
        val size = fs.metadataOrNull(archive)?.size?.coerceAtLeast(1) ?: 1
        val context = currentCoroutineContext()
        var read = 0L
        var reported = 0L
        val counting = object : RawSource {
            private val raw = fs.source(archive)

            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                context.ensureActive()
                val n = raw.readAtMostTo(sink, byteCount)
                if (n > 0) {
                    read += n
                    if (read - reported >= ProgressStep) {
                        reported = read
                        onProgress(ModelProgress.Extracting(read.toFloat() / size))
                    }
                }
                return n
            }

            override fun close() = raw.close()
        }
        onProgress(ModelProgress.Extracting(0f))
        BZip2Source(counting.buffered()).buffered().use { tar -> TarExtractor(fs).extract(tar, dir) }
        onProgress(ModelProgress.Extracting(1f))
    }

    private fun readTag(artifact: ModelArtifact): String? {
        val path = Path(dirOf(artifact), SourceTag)
        if (!fs.exists(path)) return null
        return fs.source(path).buffered().use { it.readString() }.trim()
    }

    private fun deleteIfExists(path: Path) {
        if (fs.exists(path)) fs.delete(path)
    }

    private companion object {
        const val DownloadMarker = ".dl"
        const val SourceTag = ".source"
        const val ArchiveName = "model.tar.bz2"
        const val ProgressStep = 512L * 1024
    }
}
