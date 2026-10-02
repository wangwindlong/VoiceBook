@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.wangxy.voicebook.data

import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.math.roundToInt
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.ContentApi
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.store.HistoryEntry

/** A pending local edit and a different cloud edit; neither is discarded before a choice. */
data class ReadingProgressConflict(
    val account: String,
    val server: CalibreServer,
    val local: HistoryEntry,
    val cloud: HistoryEntry,
)

class ReaderSessionRepository(
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val session: BffSession? = null,
    private val content: ContentApi? = null,
    private val calibre: CalibreWebApi? = null,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val background=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Default)
    fun endSession(bookId: Int,seconds: Long,account: String) {
        background.launch {
            flushProgress(bookId, account)
            event("reading_session",bookId,seconds,account)
        }
    }
    private val syncMutex = Mutex()
    private val localMutex = Mutex()
    private val sent = mutableMapOf<Pair<String,Int>,Pair<Long,Int>>()
    private val syncError = MutableStateFlow<String?>(null)
    val progressSyncError: StateFlow<String?> = syncError.asStateFlow()
    private val conflicts = MutableStateFlow<List<ReadingProgressConflict>>(emptyList())
    val progressConflicts: StateFlow<List<ReadingProgressConflict>> = conflicts.asStateFlow()
    private val restored = MutableSharedFlow<HistoryEntry>(extraBufferCapacity = 8)
    val restoredProgress: SharedFlow<HistoryEntry> = restored.asSharedFlow()

    val owners = session?.signedInUser ?: MutableStateFlow<String?>(null)
    fun owner(): String = session?.signedInUser?.value.orEmpty()
    suspend fun server(): CalibreServer? = library.effectiveCalibreServer(initializer, session)
    suspend fun saveServer(server: CalibreServer?) { initializer.awaitReady(); library.server.set(server) }
    fun observeHistory(): Flow<List<HistoryEntry>> = (session?.signedInUser ?: MutableStateFlow<String?>(null))
        .flatMapLatest { library.history.observeAll(it.orEmpty()) }
    suspend fun history(): List<HistoryEntry> { initializer.awaitReady(); return library.history.observeAll(owner()).first() }

    suspend fun localHistoryFor(bookId: Int): HistoryEntry? {
        initializer.awaitReady()
        return library.history.get(bookId, owner())
    }

    private fun listeningKey(server: CalibreServer?, account: String, bookId: Int) =
        "listening.position:${server?.root}:${server?.username}:$account:$bookId"

    suspend fun listeningHistoryFor(bookId: Int): HistoryEntry? {
        initializer.awaitReady()
        return library.settings.get(listeningKey(server(), owner(), bookId))?.let {
            runCatching { Json.decodeFromString<HistoryEntry>(it) }.getOrNull()
        }
    }

    /** Capture the account at playback start; late audio callbacks cannot save into a new account. */
    suspend fun listeningProgressWriter(): suspend (HistoryEntry) -> Unit {
        initializer.awaitReady()
        val account = owner()
        val connection = server()
        return { entry ->
            if (owner() == account && server() == connection) {
                library.settings.put(listeningKey(connection, account, entry.bookId), Json.encodeToString(entry.copy(pendingSync = false)))
            }
        }
    }

    suspend fun recordListeningProgress(entry: HistoryEntry) {
        listeningProgressWriter()(entry)
    }

    /** Persist the file identifier: retries and shelf refresh must also work after an app restart. */
    suspend fun prepareDocument(server: CalibreServer, bookId: Int, format: String, bytes: ByteArray) {
        initializer.awaitReady()
        if (server.viaBff || calibre == null) return
        calibre.rememberDocument(server, bookId, format, bytes)
        calibre.documentId(server, bookId, format)?.let {
            library.settings.put(documentKey(server, bookId, format), it)
        }
    }

    private fun documentKey(server: CalibreServer, bookId: Int, format: String) =
        "cwa.document.${server.root.length}:${server.root}:${server.username}:$bookId:${format.uppercase()}"

    private suspend fun documentId(server: CalibreServer, bookId: Int, format: String): String? =
        library.settings.get(documentKey(server, bookId, format))
            ?: calibre?.documentId(server, bookId, format)

    private fun baselineKey(server: CalibreServer, account: String, bookId: Int) =
        "reading.cloud.${server.root.length}:${server.root}:${server.username}:$account:$bookId"

    private suspend fun baseline(server: CalibreServer, account: String, bookId: Int): HistoryEntry? =
        library.settings.get(baselineKey(server, account, bookId))?.let {
            runCatching { Json.decodeFromString<HistoryEntry>(it) }.getOrNull()
        }

    private suspend fun rememberCloud(server: CalibreServer, account: String, entry: HistoryEntry) {
        library.settings.put(baselineKey(server, account, entry.bookId), Json.encodeToString(entry.copy(pendingSync = false)))
    }

    private fun samePosition(a: HistoryEntry, b: HistoryEntry): Boolean =
        a.format.equals(b.format, true) && a.spineIndex == b.spineIndex &&
            a.charOffset == b.charOffset && a.progress == b.progress

    private fun decodeCloud(
        metadata: HistoryEntry, format: String?, position: String?, percent: Double?, timestamp: Long?,
    ): HistoryEntry? {
        if (position == null || timestamp == null) return null
        val parts = position.split(':')
        val spine = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val offset = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= -1 } ?: return null
        return metadata.copy(format = format ?: metadata.format, spineIndex = spine, charOffset = offset,
            progress = percent?.roundToInt()?.coerceIn(0, 100) ?: 0, updatedAt = timestamp, pendingSync = false)
    }

    private suspend fun cloudFor(server: CalibreServer, account: String, metadata: HistoryEntry): HistoryEntry? {
        if (account.isNotEmpty() && content != null) {
            val remote = content.progress(metadata.bookId.toLong())
            if (remote.position == null) return null
            return decodeCloud(metadata, remote.format, remote.position, remote.percent,
                remote.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() })
                ?: throw us.wangxy.voicebook.reader.api.CalibreWebApiException("云端位置无法在当前阅读器恢复，本地进度已保留")
        }
        if (server.viaBff || calibre == null) return null
        val document = documentId(server, metadata.bookId, metadata.format)
            ?: throw us.wangxy.voicebook.reader.api.CalibreWebApiException("请重新打开书籍以准备 CWA 进度同步")
        val remote = calibre.loadKoreaderProgress(server, document) ?: return null
        if (remote.calibre_book_id != metadata.bookId) {
            throw us.wangxy.voicebook.reader.api.CalibreWebApiException("CWA 未将文件校验码关联到当前书籍，请检查服务端校验码生成")
        }
        if (remote.calibre_book_format?.equals(metadata.format, true) == false) {
            throw us.wangxy.voicebook.reader.api.CalibreWebApiException("云端进度格式与当前书籍不同，请使用相同格式同步")
        }
        return decodeCloud(metadata, remote.calibre_book_format, remote.progress, remote.percentage?.times(100),
            remote.timestamp?.times(1000))
            ?: throw us.wangxy.voicebook.reader.api.CalibreWebApiException("云端位置无法在当前阅读器恢复，本地进度已保留")
    }

    /** Cloud is authoritative for clean records. Pending records use the last acknowledged cloud position. */
    private suspend fun mergeCloud(remote: HistoryEntry, account: String, server: CalibreServer) = localMutex.withLock {
        if (owner() != account || this.server() != server) return@withLock
        val local = library.history.get(remote.bookId, account)
        if (local?.pendingSync == true && !samePosition(local, remote)) {
            val base = baseline(server, account, remote.bookId)
            if (base == null || !samePosition(base, remote)) {
                val conflict = ReadingProgressConflict(account, server, local, remote)
                conflicts.value = conflicts.value.filterNot { it.account == account && it.server == server && it.local.bookId == remote.bookId } + conflict
            }
            // Keep the original baseline until upload succeeds or the user resolves the conflict.
            return@withLock
        }
        val accepted = remote.copy(
            title = remote.title.ifBlank { local?.title.orEmpty() },
            author = remote.author.ifBlank { local?.author.orEmpty() },
            coverUrl = remote.coverUrl.ifBlank { local?.coverUrl.orEmpty() },
            seedColor = local?.seedColor ?: remote.seedColor,
        )
        library.history.upsert(accepted, account)
        rememberCloud(server, account, accepted)
        conflicts.value = conflicts.value.filterNot { it.account == account && it.server == server && it.local.bookId == remote.bookId }
    }

    suspend fun historyFor(bookId: Int, format: String? = null, metadata: HistoryEntry? = null): HistoryEntry? {
        initializer.awaitReady()
        val account = owner()
        val server = server() ?: return library.history.get(bookId, account)
        val local = library.history.get(bookId, account)
        val seed = (metadata ?: local ?: HistoryEntry(bookId, "")).copy(format = format ?: local?.format ?: "EPUB")
        syncAttempt { cloudFor(server, account, seed)?.let { mergeCloud(it, account, server) } }
        if (owner() != account || this.server() != server) return null
        library.history.get(bookId, account)?.takeIf { it.pendingSync }?.let {
            sync(it, account, true, server.takeIf { !it.viaBff })
        }
        return if (owner() == account && this.server() == server) library.history.get(bookId, account) else null
    }

    suspend fun refreshHistory() {
        initializer.awaitReady()
        val account = owner()
        val server = server() ?: return
        if (account.isNotEmpty() && content != null) {
            syncAttempt {
                content.history(200).items.forEach { item ->
                    if (owner() != account || this.server() != server) return@syncAttempt
                    val meta = HistoryEntry(item.bookId.toInt(), item.title.orEmpty(), item.author.orEmpty(),
                        item.coverUrl?.let { if (it.startsWith('/')) session!!.baseUrl() + it else it }.orEmpty())
                    decodeCloud(meta, item.format, item.position, item.percent,
                        item.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() })?.let { mergeCloud(it, account, server) }
                }
            }
            retryPendingProgress()
        } else if (!server.viaBff && calibre != null) {
            library.history.observeAll(account).first().forEach {
                if (documentId(server, it.bookId, it.format) != null) historyFor(it.bookId)
            }
        }
    }

    /** Called while the app is foregrounded; reconnect retries always compare with cloud first. */
    suspend fun retryPendingProgress() {
        initializer.awaitReady()
        val account = owner()
        val direct = server()?.takeIf { !it.viaBff }
        library.history.observeAll(account).first().filter { it.pendingSync }.forEach { sync(it, account, true, direct) }
    }

    suspend fun resolveConflict(conflict: ReadingProgressConflict, useCloud: Boolean) {
        var upload: HistoryEntry? = null
        syncAttempt {
            syncMutex.withLock {
                if (owner() != conflict.account || server() != conflict.server) return@withLock
                if (conflict !in conflicts.value) return@withLock
                // Re-read cloud before choosing so an old dialog cannot overwrite a third device's update.
                val latest = cloudFor(conflict.server, conflict.account, conflict.local)
                localMutex.withLock local@{
                    if (owner() != conflict.account || server() != conflict.server) return@local
                    val current = library.history.get(conflict.local.bookId, conflict.account) ?: return@local
                    if (useCloud) {
                        val selected = latest ?: throw IllegalStateException("云端暂无可恢复进度，请保留本机进度")
                        library.history.upsert(selected.copy(seedColor = current.seedColor), conflict.account)
                        rememberCloud(conflict.server, conflict.account, selected)
                        restored.tryEmit(selected)
                    } else {
                        upload = current.copy(pendingSync = true, updatedAt = nowMillis())
                        library.history.upsert(upload!!, conflict.account)
                        if (latest != null) rememberCloud(conflict.server, conflict.account, latest)
                    }
                    conflicts.value = conflicts.value - conflict
                }
            }
        }
        upload?.let { sync(it, conflict.account, true, conflict.server.takeIf { !it.viaBff }) }
    }

    suspend fun updateHistorySeed(bookId: Int,color: Int) {
        initializer.awaitReady(); val account=owner()
        library.history.get(bookId,account)?.let { library.history.upsert(it.copy(seedColor=color),account) }
    }
    suspend fun recordProgress(entry: HistoryEntry, account: String = owner()) {
        initializer.awaitReady()
        val directServer = server()?.takeIf { !it.viaBff }
        val saved=localMutex.withLock {
            if (account != owner()) return
            if (conflicts.value.any { it.account == account && it.server == server() && it.local.bookId == entry.bookId }) return
            val previous=library.history.get(entry.bookId,account)
            if (previous?.pendingSync == true && previous.updatedAt > entry.updatedAt) return
            entry.copy(seedColor=entry.seedColor ?: previous?.seedColor,pendingSync=account.isNotEmpty() || directServer != null).also {
                library.history.upsert(it,account)
            }
        }
        sync(saved,account,false,directServer)
    }
    private suspend fun sync(entry: HistoryEntry,account: String,force: Boolean,directServer: CalibreServer? = null) = syncMutex.withLock {
        if (account != owner()) return@withLock
        val useDirect = account.isEmpty() && directServer != null && calibre != null
        if (!useDirect && (account.isEmpty() || content == null)) return@withLock
        if (useDirect && server() != directServer) return@withLock
        if (library.history.get(entry.bookId, account) != entry) return@withLock
        val key=(if (useDirect) "direct:${directServer!!.root}:${directServer.username}" else account) to entry.bookId
        val now=nowMillis()
        val last=sent[key]
        if (!force && last != null && now-last.first < 15_000 && kotlin.math.abs(entry.progress-last.second)<1) return@withLock
        if (conflicts.value.any { it.account == account && it.server == server() && it.local.bookId == entry.bookId }) return@withLock
        syncAttempt {
            val activeServer = server() ?: return@syncAttempt
            // Never blindly upload a queued offline position over a changed cloud position.
            cloudFor(activeServer, account, entry)?.let { mergeCloud(it, account, activeServer) }
            if (owner() != account || server() != activeServer) return@syncAttempt
            if (conflicts.value.any { it.account == account && it.server == activeServer && it.local.bookId == entry.bookId }) return@syncAttempt
            if (library.history.get(entry.bookId, account) != entry) return@syncAttempt
            var acknowledgedAt = entry.updatedAt
            if (useDirect) {
                val document = documentId(directServer!!, entry.bookId, entry.format)
                    ?: throw us.wangxy.voicebook.reader.api.CalibreWebApiException("请重新打开书籍以准备 CWA 进度同步")
                val ack = calibre!!.saveKoreaderProgress(directServer, document, "${entry.spineIndex}:${entry.charOffset}", entry.progress)
                if (ack.calibre_book_id != entry.bookId) throw us.wangxy.voicebook.reader.api.CalibreWebApiException("CWA 已收到进度但未关联到当前书籍，请检查服务端校验码生成")
                acknowledgedAt = ack.timestamp!! * 1000
            }
            else content!!.setProgress(entry.bookId.toLong(),ReadingProgressUpdate(entry.format,"${entry.spineIndex}:${entry.charOffset}",entry.progress.toDouble()),expectedOwner=account)
            sent[key]=now to entry.progress
            localMutex.withLock {
                val current=library.history.get(entry.bookId,account)
                if (owner() == account && server() == activeServer) {
                    val confirmed = entry.copy(pendingSync = false, updatedAt = acknowledgedAt)
                    // A newer local edit may have arrived during PUT. Acknowledge the uploaded
                    // baseline anyway, but keep that newer edit pending for the next upload.
                    rememberCloud(activeServer, account, confirmed)
                    if (current == entry) library.history.upsert(confirmed, account)
                    conflicts.value = conflicts.value.filterNot {
                        it.account == account && it.server == activeServer &&
                            it.local.bookId == entry.bookId && samePosition(it.cloud, confirmed)
                    }
                }
            }
        }
    }
    suspend fun flushProgress(bookId: Int, account: String = owner()) {
        initializer.awaitReady()
        if (account != owner()) return
        library.history.get(bookId, account)?.takeIf { it.pendingSync }?.let {
            sync(it, account, true, server()?.takeIf { !it.viaBff })
        }
    }

    private suspend fun syncAttempt(block: suspend () -> Unit) {
        try { block(); syncError.value = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { syncError.value = e.message ?: "进度同步失败，已保存在本地等待重试" }
    }
    suspend fun event(kind: String,bookId: Int,seconds: Long? = null,account: String = owner()) {
        if (account.isEmpty() || account != owner() || library.settings.get("personalization.enabled") == "false") return
        quietly { content?.events(listOf(BehaviorEvent(kind,"book",bookId.toString(),ts=Clock.System.now().toString(),seconds=seconds)),expectedOwner=account) }
    }
    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { /* Keep pending local progress for next open. */ }
    }
}
