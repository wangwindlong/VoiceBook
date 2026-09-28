package us.wangxy.voicebook.screens.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import us.wangxy.voicebook.reader.paginate.ReaderPage

/** 章界手势:只有顶到章首/章末、并且横向滑过或甩动,才跨章。 */
class BoundaryChapterTurnTest {

    @Test
    fun swipeForwardAtEndGoesToNextChapter() {
        assertEquals(
            BoundaryTurn.Next,
            boundaryChapterTurn(
                atStart = false,
                atEnd = true,
                dx = -80f,
                dy = 4f,
                velocityX = 0f,
                velocityY = 0f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }

    @Test
    fun swipeBackwardAtStartGoesToPreviousChapter() {
        assertEquals(
            BoundaryTurn.Previous,
            boundaryChapterTurn(
                atStart = true,
                atEnd = false,
                dx = 80f,
                dy = 2f,
                velocityX = 0f,
                velocityY = 0f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }

    @Test
    fun flickForwardAtEndGoesToNextChapter() {
        assertEquals(
            BoundaryTurn.Next,
            boundaryChapterTurn(
                atStart = false,
                atEnd = true,
                dx = -20f,
                dy = 1f,
                velocityX = -900f,
                velocityY = 10f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }

    @Test
    fun inwardSwipeAtEndStaysInChapter() {
        assertEquals(
            null,
            boundaryChapterTurn(
                atStart = false,
                atEnd = true,
                dx = 80f,
                dy = 0f,
                velocityX = 0f,
                velocityY = 0f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }

    @Test
    fun forwardSwipeBeforeTheEndStaysInChapter() {
        assertEquals(
            null,
            boundaryChapterTurn(
                atStart = false,
                atEnd = false,
                dx = -80f,
                dy = 0f,
                velocityX = -900f,
                velocityY = 0f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }

    @Test
    fun forwardExtendKeepsEarlierPagesAtTheSameIndex() {
        val slots = chapterSlots(chapter = 1, pages = 3)
        val extended = extendForward(slots, chapter = 2, pages = pages(2))
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1), extended.map { it.chapter to it.pageInChapter })
        assertEquals(extended, extendForward(extended, chapter = 2, pages = pages(2)))
        assertEquals(slots, extendForward(slots, chapter = 4, pages = pages(1)))
    }

    @Test
    fun backwardExtendShiftsTheVisibleIndexByThePrependedCount() {
        val slots = chapterSlots(chapter = 2, pages = 2)
        val (extended, shift) = extendBackward(slots, chapter = 1, pages = pages(3))
        assertEquals(3, shift)
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1), extended.map { it.chapter to it.pageInChapter })
        assertEquals(3, indexOfSlot(extended, chapter = 2, pageInChapter = 0))
        val (unchanged, none) = extendBackward(extended, chapter = 0, pages = emptyList())
        assertEquals(0, none)
        assertEquals(extended, unchanged)
    }

    @Test
    fun verticalDragDoesNotCrossChapters() {
        assertEquals(
            null,
            boundaryChapterTurn(
                atStart = true,
                atEnd = true,
                dx = 10f,
                dy = 90f,
                velocityX = 20f,
                velocityY = 800f,
                minDragPx = 48f,
                minFlingVelocity = 200f,
            ),
        )
    }
}

private fun pages(count: Int): List<ReaderPage> =
    List(count) { ReaderPage(emptyList(), it) }

private fun chapterSlots(chapter: Int, pages: Int): List<StreamSlot> =
    pages(pages).mapIndexed { index, page -> StreamSlot(chapter, index, page) }
