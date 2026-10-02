package us.wangxy.voicebook.screens.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingProgressTest {
    @Test
    fun finalPageCompletesMultiChapterBook() {
        assertEquals(100, readingProgressPercent(2, 3, 6, 7))
        assertTrue(readingProgressPercent(2, 3, 5, 7) < 100)
    }

    @Test
    fun progressAdvancesWithinChapter() {
        assertEquals(60, readingProgressPercent(1, 2, 0, 5))
        assertEquals(70, readingProgressPercent(1, 2, 1, 5))
        assertEquals(50, readingProgressPercent(0, 2, 4, 5))
    }

    @Test
    fun pdfIncludesDisplayedPage() {
        assertEquals(1, readingProgressPercent(0, 1, 0, 100))
        assertEquals(99, readingProgressPercent(0, 1, 98, 100))
        assertEquals(100, readingProgressPercent(0, 1, 99, 100))
    }

    @Test
    fun singlePageBookIsComplete() {
        assertEquals(100, readingProgressPercent(0, 1, 0, 1))
    }

    @Test
    fun missingPagesDoNotReportCompletion() {
        assertEquals(0, readingProgressPercent(0, 1, 0, 0))
        assertEquals(0, readingProgressPercent(0, 0, 0, 1))
    }
}
