package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

actual fun createBookBytesCache(): BookBytesCache = WebBookBytesCache

/**
 * 字节缓存的内存实现：书籍正文体积大，localStorage 放不下；
 * 会话内离线重开有效，刷新后失效（命中与否由 ReaderScreen 回退网络决定）。
 */
private object WebBookBytesCache : BookBytesCache {

    private val store = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    override suspend fun get(key: String): ByteArray? = store.value[key]

    override suspend fun put(key: String, bytes: ByteArray) {
        store.update { it + (key to bytes) }
    }

    override suspend fun evict(key: String) {
        store.update { it - key }
    }
}
