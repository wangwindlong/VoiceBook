package us.wangxy.voicebook.data

/**
 * 已下载书籍正文的本地缓存。命中则阅读器免下载直接打开。
 * 键 = "baseUrl|bookId|format"：换服务器自动失效（不同库的 bookId 不互通），
 * 各服务器的缓存互不干扰，切回旧服务器仍可离线续读。
 */
interface BookBytesCache {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, bytes: ByteArray)

    /** 移除缓存条目:解析失败(内容损坏/曾缓存过错误页)时调用,强制重下。 */
    suspend fun evict(key: String)
}

expect fun createBookBytesCache(): BookBytesCache

/** 缓存键转安全文件名：非法字符统一替换，键形如 "http://ip:8083|42|EPUB"。 */
internal expect fun fileNameFor(key: String): String
