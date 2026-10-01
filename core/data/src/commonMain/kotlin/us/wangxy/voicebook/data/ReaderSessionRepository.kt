@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.wangxy.voicebook.data

import kotlinx.coroutines.launch
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

    suspend fun historyFor(bookId: Int, format: String? = null, metadata: HistoryEntry? = null): HistoryEntry? {
        initializer.awaitReady()
        val account = owner()
        var local = library.history.get(bookId,account)
        if (account.isNotEmpty() && content != null) quietly {
            val remote = content.progress(bookId.toLong())
            if (owner() != account) return@quietly
            merge(remote.bookId, remote.format,remote.position,remote.percent,remote.updatedAt,account)
        }
        local=library.history.get(bookId,account)
        val direct = server()?.takeIf { !it.viaBff }
        if (direct != null && calibre != null) {
            val resolvedFormat = format ?: local?.format ?: "EPUB"
            val document = documentId(direct, bookId, resolvedFormat)
            if (document != null) syncAttempt {
                val remote = calibre.loadKoreaderProgress(direct, document)
                if (owner() != account || server() != direct) return@syncAttempt
                val remotePosition = remote?.progress
                val remotePercentage = remote?.percentage
                val timestamp = remote?.timestamp?.times(1000)
                if (remotePosition != null && remotePercentage != null && timestamp != null) {
                    if (remote.calibre_book_id != bookId) throw us.wangxy.voicebook.reader.api.CalibreWebApiException("CWA 未将文件校验码关联到当前书籍，请检查服务端校验码生成")
                    // VoiceBook's chapter/character anchors cannot be interpreted as KOReader XPath/CFI.
                    if (remote.calibre_book_format?.equals(resolvedFormat, true) == false) return@syncAttempt
                    val parts = remotePosition.split(':')
                    val spine = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return@syncAttempt
                    val offset = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= -1 } ?: return@syncAttempt
                    localMutex.withLock {
                        val current = library.history.get(bookId, account)
                        // A local unsent edit wins within the same second (KOSync timestamps have second precision).
                        if (current?.pendingSync == true && current.updatedAt / 1000 >= timestamp / 1000) return@withLock
                        if (current != null && timestamp < current.updatedAt) return@withLock
                        library.history.upsert(
                            (current ?: metadata ?: HistoryEntry(bookId, "")).copy(
                                format = resolvedFormat, spineIndex = spine, charOffset = offset, updatedAt = timestamp,
                                progress = (remotePercentage * 100).roundToInt().coerceIn(0, 100), pendingSync = false,
                            ), account,
                        )
                    }
                }
            }
        }
        local=library.history.get(bookId,account)
        if(local?.pendingSync == true) sync(local,account,true, direct)
        return if (owner() == account) library.history.get(bookId,account) else null
    }

    /** UI observes local rows immediately; sync only adds newer server records. */
    suspend fun refreshHistory() {
        initializer.awaitReady()
        val account = owner()
        val directServer = server()?.takeIf { !it.viaBff }
        if ((account.isEmpty() && directServer == null) || (account.isNotEmpty() && content == null)) return
        quietly {
            if (account.isNotEmpty()) {
                val page = content!!.history(200)
                if (owner() != account) return@quietly
                page.items.forEach { item -> merge(item.bookId,item.format,item.position,item.percent,item.updatedAt,account,item) }
            }
        }
        if (directServer != null) {
            library.history.observeAll(account).first().forEach { historyFor(it.bookId) }
        } else {
            library.history.observeAll(account).first().filter { it.pendingSync }.forEach { sync(it,account,true) }
        }
    }
    private suspend fun merge(id: Long, format: String?, position: String?, percent: Double?, updated: String?, account: String, item: ReadingHistoryItem? = null) = localMutex.withLock {
        val local = library.history.get(id.toInt(),account)
        val timestamp = updated?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return
        if (timestamp <= (local?.updatedAt ?: 0)) return
        val parts = position?.split(':') ?: return
        val spine = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return
        val offset = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= -1 } ?: return
        library.history.upsert((local ?: HistoryEntry(id.toInt(),item?.title.orEmpty())).copy(
            title=item?.title ?: local?.title.orEmpty(),author=item?.author ?: local?.author.orEmpty(),
            coverUrl=item?.coverUrl?.let { if (it.startsWith('/')) session!!.baseUrl()+it else it } ?: local?.coverUrl.orEmpty(),
            format=format ?: local?.format ?: "EPUB",spineIndex=spine,charOffset=offset,
            progress=percent?.toInt() ?: 0,updatedAt=timestamp,pendingSync=false),account)
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
            val previous=library.history.get(entry.bookId,account)
            if (previous != null && previous.updatedAt > entry.updatedAt) return
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
        syncAttempt {
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
                if (current == entry) library.history.upsert(current.copy(pendingSync=false, updatedAt=acknowledgedAt),account)
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
