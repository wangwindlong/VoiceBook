package us.wangxy.voicebook.reader.epub

/**
 * Reader-facing content of a book regardless of container format (EPUB, plain text).
 * Chapters reuse [EpubBook.Chapter]; blocks are built lazily per chapter by the
 * implementation.
 */
interface ReadableBook {
    val chapters: List<EpubBook.Chapter>

    fun chapterBlocks(index: Int): List<Block>

    /** Raw bytes for an image referenced inside a chapter; null when the format has none. */
    fun resourceBytes(hrefInChapter: String, chapterHref: String?): ByteArray? = null
}
