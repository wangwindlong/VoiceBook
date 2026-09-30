@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.wangxy.voicebook.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
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
        background.launch { event("reading_session",bookId,seconds,account) }
    }
    private val syncMutex = Mutex()
    private val localMutex = Mutex()
    private val sent = mutableMapOf<Pair<String,Int>,Pair<Long,Int>>()
    val owners = session?.signedInUser ?: MutableStateFlow<String?>(null)
    fun owner(): String = session?.signedInUser?.value.orEmpty()
    suspend fun server(): CalibreServer? = library.effectiveCalibreServer(initializer, session)
    suspend fun saveServer(server: CalibreServer?) { initializer.awaitReady(); library.server.set(server) }
    fun observeHistory(): Flow<List<HistoryEntry>> = (session?.signedInUser ?: MutableStateFlow<String?>(null))
        .flatMapLatest { library.history.observeAll(it.orEmpty()) }
    suspend fun history(): List<HistoryEntry> { initializer.awaitReady(); return library.history.observeAll(owner()).first() }

    suspend fun historyFor(bookId: Int): HistoryEntry? {
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
            val document = calibre.documentId(direct, bookId, local?.format ?: "EPUB")
            if (document != null) quietly {
                val remote = calibre.loadKoreaderProgress(direct, document)
                val remotePosition = remote?.progress
                val remotePercentage = remote?.percentage
                if (remotePosition != null && remotePercentage != null) {
                    val parts = remotePosition.split(':')
                    val spine = parts.getOrNull(0)?.toIntOrNull() ?: return@quietly
                    val offset = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    localMutex.withLock {
                        val current = library.history.get(bookId, account)
                        if (current == null || current.updatedAt <= 0L) library.history.upsert(
                            (current ?: HistoryEntry(bookId, "")).copy(
                                format = local?.format ?: "EPUB", spineIndex = spine, charOffset = offset,
                                progress = (remotePercentage * 100).toInt().coerceIn(0, 100), pendingSync = false,
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
        library.history.observeAll(account).first().filter { it.pendingSync }.forEach { sync(it,account,true,directServer) }
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
        val useDirect = account.isEmpty() && directServer != null && calibre != null
        if ((!useDirect && (account.isEmpty() || account != owner() || content == null)) || (useDirect && directServer == null)) return@withLock
        val key=(if (useDirect) "direct:${directServer!!.root}:${directServer.username}" else account) to entry.bookId
        val now=nowMillis()
        val last=sent[key]
        if (!force && last != null && now-last.first < 15_000 && kotlin.math.abs(entry.progress-last.second)<1) return@withLock
        quietly {
            if (useDirect) {
                val document = calibre!!.documentId(directServer!!, entry.bookId, entry.format)
                if (document != null) {
                    try {
                        calibre.saveKoreaderProgress(directServer, document, "${entry.spineIndex}:${entry.charOffset}", entry.progress)
                    } catch (_: Exception) {
                        // Older/non-CWA calibre-web instances only expose the web-reader bookmark API.
                        calibre.saveBookmark(directServer, entry.bookId, entry.format, "${entry.spineIndex}:${entry.charOffset}")
                    }
                } else calibre.saveBookmark(directServer!!,entry.bookId,entry.format,"${entry.spineIndex}:${entry.charOffset}")
            }
            else content!!.setProgress(entry.bookId.toLong(),ReadingProgressUpdate(entry.format,"${entry.spineIndex}:${entry.charOffset}",entry.progress.toDouble()),expectedOwner=account)
            sent[key]=now to entry.progress
            localMutex.withLock {
                val current=library.history.get(entry.bookId,account)
                if (current == entry) library.history.upsert(current.copy(pendingSync=false),account)
            }
        }
    }
    suspend fun event(kind: String,bookId: Int,seconds: Long? = null,account: String = owner()) {
        if (account.isEmpty() || account != owner() || library.settings.get("personalization.enabled") == "false") return
        quietly { content?.events(listOf(BehaviorEvent(kind,"book",bookId.toString(),ts=Clock.System.now().toString(),seconds=seconds)),expectedOwner=account) }
    }
    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { /* Keep pending local progress for next open. */ }
    }
}
