@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreServerAccount
import us.wangxy.voicebook.reader.api.calibreServer
import us.wangxy.voicebook.reader.api.CalibreWebApi
import us.wangxy.voicebook.reader.opds.OpdsEntry

/** calibre-web's /opds/new page size; keep paging keys aligned with it. */
const val SHELF_PAGE_SIZE = 20

/**
 * Cache-first shelf data: the local [BookCacheStore] (SQLDelight or web storage) is
 * the single read path, so a cold start renders the last-known shelf instantly and
 * OPDS fetches only fill the pages that are missing. Inspired by Twine's
 * repository + QueryPagingSource split, adapted to a store interface that also has
 * a web implementation.
 */
class BookRepository(
    private val api: CalibreWebApi,
    private val library: LocalLibrary,
    private val initializer: LibraryInitializer,
    private val session: BffSession? = null,
) {

    private val errorFlow = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    private val serverVersionFlow = MutableStateFlow(0)

    /** 递增于生效服务器变化（保存/切换账号、登录/退出统一账号）；各屏订阅它来重载书架。 */
    val serverVersion: StateFlow<Int> = serverVersionFlow.asStateFlow()

    /** 生效的书库：已登录统一账号时是 BFF，否则是「我的」里配置的 calibre-web。 */
    suspend fun server(): CalibreServer? = library.effectiveCalibreServer(initializer, session)

    /** 「我的」里手动配置的 calibre-web（退出统一账号后生效），与登录状态无关。 */
    suspend fun configuredServer(): CalibreServer? {
        initializer.awaitReady()
        return library.server.get()
    }

    /** 登录/退出统一账号：书库后端变了，清掉书架缓存并通知各屏重载。 */
    suspend fun onSessionChanged() {
        initializer.awaitReady()
        serverVersionFlow.value++
    }

    suspend fun saveServer(server: CalibreServer) {
        initializer.awaitReady()
        library.server.set(server)
        // 自动归档为可切换账号（本地配置过的书架都保留）。
        library.calibreAccounts.upsert(
            CalibreServerAccount(
                id = serverAccountId(server),
                label = server.baseUrl,
                baseUrl = server.baseUrl,
                username = server.username,
                password = server.password,
                lastUsedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        library.bookCache.clear(owner())
        serverVersionFlow.value++
    }

    suspend fun savedServers(): List<CalibreServerAccount> {
        initializer.awaitReady()
        return library.calibreAccounts.saved()
    }

    /** 切换到已保存的服务器：替换生效配置并清空书架缓存（各库的书不同）。 */
    suspend fun activateServerAccount(id: String): Boolean {
        initializer.awaitReady()
        val account = library.calibreAccounts.saved().firstOrNull { it.id == id } ?: return false
        val server = CalibreServer(account.baseUrl, account.username, account.password)
        library.server.set(server)
        library.bookCache.clear(owner())
        library.calibreAccounts.upsert(account.copy(lastUsedAt = Clock.System.now().toEpochMilliseconds()))
        serverVersionFlow.value++
        return true
    }

    suspend fun deleteServerAccount(id: String) {
        initializer.awaitReady()
        library.calibreAccounts.delete(id)
    }

    private fun serverAccountId(server: CalibreServer): String =
        "${server.baseUrl}|${server.username}"

    fun owner(): String = session?.signedInUser?.value.orEmpty()
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun refresh() = refreshMutex.withLock {
        val account = owner()
        val server = server() ?: return@withLock
        try {
            val first = api.newest(server,0)
            if (owner() != account) return@withLock
            val key = "calibre.libraryVersion:$account:${server.baseUrl}"
            val unchanged = first.libraryVersion != null && first.libraryVersion == library.settings.get(key)
            if (unchanged) {
                library.bookCache.upsertAll(first.entries.mapIndexed { i,e -> e.cached(server,i) },account)
            } else {
                val all = first.entries.toMutableList()
                var next = first.nextOffset
                // Legacy feeds without versions remain paged on demand.
                while (first.libraryVersion != null && next != null) {
                    kotlinx.coroutines.yield()
                    val page = api.newest(server,next)
                    if (page.libraryVersion != first.libraryVersion) return@withLock
                    all += page.entries
                    val offset = page.nextOffset
                    if (offset != null && offset <= next) throw IllegalStateException("分页未前进")
                    next = offset
                }
                if (owner() != account) return@withLock
                library.bookCache.clear(account)
                library.bookCache.upsertAll(all.mapIndexed { i,e -> e.cached(server,i) },account)
                first.libraryVersion?.let { library.settings.put(key,it) }
            }
            errorFlow.value = if (first.entries.isEmpty()) "该分类下暂无书籍" else null
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) { errorFlow.value = "离线，仅显示已缓存" }
    }

    fun shelfPager(): Flow<PagingData<CachedBook>> = pager(LibraryFilter())
    fun filteredPager(query: String,category: String,tags: List<String> = emptyList(),tagMode: String = "any",sort: String = "added"): Flow<PagingData<CachedBook>> =
        pager(LibraryFilter(query,category,tags,tagMode,sort))
    private fun pager(filter: LibraryFilter) = Pager(
        PagingConfig(pageSize=SHELF_PAGE_SIZE,prefetchDistance=6,enablePlaceholders=false),
        pagingSourceFactory={ LibraryPagingSource(this,SHELF_PAGE_SIZE,filter) },
    ).flow

    suspend fun cachedFilter(filter: LibraryFilter): List<CachedBook> {
        initializer.awaitReady()
        val books = library.bookCache.page(Int.MAX_VALUE,0,owner()).filter { book ->
            (filter.query.isBlank() || book.title.contains(filter.query,true) || book.author.contains(filter.query,true)) &&
            (filter.category == "全部" || filter.category in book.tags || (filter.category == "电子书" && book.bookId < 0)) &&
            (filter.tags.isEmpty() || if (filter.tagMode == "all") book.tags.containsAll(filter.tags) else filter.tags.any { it in book.tags })
        }
        return when(filter.sort) { "title" -> books.sortedBy { it.title }; "author" -> books.sortedBy { it.author }; "rating" -> books.sortedByDescending { it.rating }; "pubdate" -> books.sortedByDescending { it.pubdate }; else -> books }
    }

    suspend fun page(pageSize: Int,pageIndex: Int,filter: LibraryFilter = LibraryFilter()): List<CachedBook> {
        initializer.awaitReady()
        val account=owner(); val offset=pageIndex*pageSize
        val cached=library.bookCache.page(pageSize,offset,account)
        val server=server()
        if (filter == LibraryFilter()) {
            if (cached.isNotEmpty() || server == null) return cached
            // A complete versioned cache has a known end; don't re-fetch its empty last page.
            if (offset > 0 && library.settings.get("calibre.libraryVersion:$account:${server.baseUrl}") != null) return emptyList()
        }
        if (server == null) return cachedFilter(filter).drop(offset).take(pageSize)
        return try {
            val feed = if (filter == LibraryFilter()) api.newest(server,offset)
                else api.catalogue(server,filter.query,filter.category,offset,filter.tags,filter.tagMode,filter.sort)
            val books=feed.entries.mapIndexed { index,e -> e.cached(server,offset+index) }
            if (owner() != account) return emptyList()
            if (filter == LibraryFilter()) library.bookCache.upsertAll(books,account)
            errorFlow.value=null
            books
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) { errorFlow.value="离线，仅显示已缓存"; cachedFilter(filter).drop(offset).take(pageSize) }
    }

    private fun OpdsEntry.cached(server: CalibreServer,pos: Int) = CachedBook(
        bookId,title,author,api.coverUrl(server,this),epubHref.orEmpty(),pos,
        tags=tags,series=series,publisher=publisher,rating=rating,pageCount=pageCount,languages=languages,
        identifiers=identifiers,edition=edition,pubdate=pubdate,lastModified=lastModified,description=description,
    )

    suspend fun search(server: CalibreServer, query: String): List<OpdsEntry> {
        val result=LibraryPagingSource(this,SHELF_PAGE_SIZE,LibraryFilter(query)).load(PagingSource.LoadParams.Refresh(null,SHELF_PAGE_SIZE,false))
        return (result as? PagingSource.LoadResult.Page)?.data.orEmpty().map { book ->
            OpdsEntry(book.bookId,book.title,book.author,book.description.orEmpty(),book.coverUrl,book.epubHref,
                tags=book.tags,series=book.series,publisher=book.publisher,rating=book.rating,pageCount=book.pageCount,languages=book.languages,
                identifiers=book.identifiers,edition=book.edition,pubdate=book.pubdate,lastModified=book.lastModified,description=book.description)
        }
    }

    fun coverAuthHeader(server: CalibreServer): String? = api.coverAuthHeader(server)

    /** 阅读历史的封面：历史里存的是当时书库的地址，换了后端（登录/退出）时按书 id 重建。 */
    fun historyCoverUrl(server: CalibreServer, bookId: Int, storedUrl: String): String =
        api.coverUrlForBook(server, bookId, storedUrl)

    /** 启动/切换链路的失败原因，供空态页面展示与崩溃日志留存。 */
    fun setError(message: String?) {
        errorFlow.value = message
    }

}

/** Signed in → the BFF catalog; otherwise the calibre-web server configured on the 我的 page. */
internal suspend fun LocalLibrary.effectiveCalibreServer(
    initializer: LibraryInitializer,
    session: BffSession?,
): CalibreServer? {
    session?.calibreServer()?.let { return it }
    initializer.awaitReady()
    return server.get()
}

/**
 * PagingSource over the cache-first repository. Keys are page indexes (not byte
 * offsets); a full page assumes more may exist and the next load self-corrects
 * (cache miss → OPDS fetch → empty page terminates the pager).
 */
class LibraryPagingSource(
    private val repository: BookRepository,
    private val pageSize: Int,
    private val filter: LibraryFilter = LibraryFilter(),
) : PagingSource<Int, CachedBook>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CachedBook> {
        val pageIndex = params.key ?: 0
        return try {
            val books = repository.page(pageSize, pageIndex, filter)
            LoadResult.Page(
                data = books,
                prevKey = if (pageIndex == 0) null else pageIndex - 1,
                nextKey = if (books.size < pageSize) null else pageIndex + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CachedBook>): Int? =
        state.anchorPosition?.let { anchor -> anchor / pageSize }
}

data class LibraryFilter(val query: String = "",val category: String = "全部",val tags: List<String> = emptyList(),val tagMode: String = "any",val sort: String = "added")
