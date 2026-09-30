@file:OptIn(kotlin.time.ExperimentalTime::class)

package us.wangxy.voicebook.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
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
        library.bookCache.clear()
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
        library.bookCache.clear()
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
        library.bookCache.clear()
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

    /** Pull-to-refresh: wipe the cache and re-fetch the first OPDS page. */
    suspend fun refresh() {
        val server = server() ?: return
        // 先拉取解析，成功才替换缓存——失败时保留旧数据（离线/抖动不清空书架）。
        val fetched = fetchParsed(server, 0)
        if (fetched === null) return
        library.bookCache.clear()
        library.bookCache.upsertAll(fetched)
        errorFlow.value = if (fetched.isEmpty()) "书架是空的" else null
    }

    fun shelfPager(): Flow<PagingData<CachedBook>> = Pager(
        config = PagingConfig(pageSize = SHELF_PAGE_SIZE, prefetchDistance = 6, enablePlaceholders = false),
        pagingSourceFactory = { LibraryPagingSource(this, SHELF_PAGE_SIZE) },
    ).flow

    /** Filter on the server before paging; never filters just the currently visible page. */
    fun filteredPager(query: String, category: String): Flow<PagingData<CachedBook>> = Pager(
        PagingConfig(pageSize = SHELF_PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { object : PagingSource<Int, CachedBook>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CachedBook> = try {
                val offset = params.key ?: 0
                val server = server()
                val feed = server?.let { api.catalogue(it, query, category, offset) }
                val items = feed?.entries.orEmpty().mapIndexed { index, entry ->
                    CachedBook(entry.bookId, entry.title, entry.author, api.coverUrl(server!!, entry), entry.epubHref.orEmpty(), offset + index)
                }
                LoadResult.Page(items, null, feed?.nextOffset)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { LoadResult.Error(e) }
            override fun getRefreshKey(state: PagingState<Int, CachedBook>): Int? = null
        } },
    ).flow

    /** One shelf page for the paging source: read cache first, fetch OPDS on a miss. */
    suspend fun page(pageSize: Int, pageIndex: Int): List<CachedBook> {
        val server = server() ?: return emptyList()
        val offset = pageIndex * pageSize
        val cached = library.bookCache.page(pageSize, offset)
        if (cached.isNotEmpty()) return cached
        val fetched = fetchIntoCache(server, offset)
        if (fetched.isEmpty() && offset == 0) errorFlow.value = "书架是空的"
        return library.bookCache.page(pageSize, offset)
    }

    /** Server-side search bypasses the cache; result sets are small. */
    suspend fun search(server: CalibreServer, query: String): List<OpdsEntry> {
        initializer.awaitReady()
        return try {
            api.search(server, query).entries.also { errorFlow.value = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorFlow.value = e.message ?: "搜索失败"
            emptyList()
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

    private suspend fun fetchParsed(server: CalibreServer, offset: Int): List<CachedBook>? {
        return try {
            val feed = api.newest(server, offset)
            val books = feed.entries.mapIndexed { index, entry ->
                CachedBook(
                    bookId = entry.bookId,
                    title = entry.title,
                    author = entry.author,
                    coverUrl = api.coverUrl(server, entry),
                    epubHref = entry.epubHref ?: "",
                    pos = offset + index,
                )
            }
            if (offset == 0) errorFlow.value = null
            books
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorFlow.value = e.message ?: "加载书架失败"
            null
        }
    }

    private suspend fun fetchIntoCache(server: CalibreServer, offset: Int): List<CachedBook> {
        val books = fetchParsed(server, offset) ?: return emptyList()
        library.bookCache.upsertAll(books)
        return books
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
) : PagingSource<Int, CachedBook>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CachedBook> {
        val pageIndex = params.key ?: 0
        return try {
            val books = repository.page(pageSize, pageIndex)
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
