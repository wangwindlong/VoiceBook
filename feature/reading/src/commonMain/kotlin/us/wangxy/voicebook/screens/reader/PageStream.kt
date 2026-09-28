package us.wangxy.voicebook.screens.reader

import us.wangxy.voicebook.reader.paginate.ReaderPage

/**
 * 阅读会话里的一页。章节只提供目录和排版来源,翻页条拿的是这份会往两端生长的页流:
 * 往后读接上下一章,往前读接上上一章,已经排好的页留在原处,不因跨章整表拆掉重建。
 */
internal data class StreamSlot(
    val chapter: Int,
    val pageInChapter: Int,
    val page: ReaderPage,
)

internal fun indexOfSlot(slots: List<StreamSlot>, chapter: Int, pageInChapter: Int): Int =
    slots.indexOfFirst { it.chapter == chapter && it.pageInChapter == pageInChapter }

/** 在页流末尾接上紧邻的下一章。章号不连续、或这一章已经在流里时,原样返回。 */
internal fun extendForward(
    slots: List<StreamSlot>,
    chapter: Int,
    pages: List<ReaderPage>,
): List<StreamSlot> {
    if (pages.isEmpty() || slots.isEmpty()) return slots
    if (chapter != slots.last().chapter + 1) return slots
    return slots + pages.mapIndexed { index, page -> StreamSlot(chapter, index, page) }
}

/**
 * 在页流开头接上紧邻的上一章。返回新列表,以及为了让原来看见的那一页留在原地,
 * 翻页下标需要增加的页数。章号不连续时不改列表,增量为 0。
 */
internal fun extendBackward(
    slots: List<StreamSlot>,
    chapter: Int,
    pages: List<ReaderPage>,
): Pair<List<StreamSlot>, Int> {
    if (pages.isEmpty() || slots.isEmpty()) return slots to 0
    if (chapter != slots.first().chapter - 1) return slots to 0
    val added = pages.mapIndexed { index, page -> StreamSlot(chapter, index, page) }
    return (added + slots) to added.size
}
