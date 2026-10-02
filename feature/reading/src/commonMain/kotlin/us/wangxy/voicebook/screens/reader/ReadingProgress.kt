package us.wangxy.voicebook.screens.reader

/** Each chapter has an equal share; progress includes the currently displayed page. */
internal fun readingProgressPercent(
    chapterIndex: Int,
    chapterCount: Int,
    pageIndex: Int,
    pageCount: Int,
): Int {
    if (chapterCount <= 0 || pageCount <= 0) return 0
    val chapter = chapterIndex.coerceIn(0, chapterCount - 1).toLong()
    val page = pageIndex.coerceIn(0, pageCount - 1).toLong()
    return ((chapter * pageCount + page + 1) * 100 / (chapterCount.toLong() * pageCount)).toInt()
}
