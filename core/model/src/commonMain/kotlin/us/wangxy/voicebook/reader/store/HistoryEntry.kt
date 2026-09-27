package us.wangxy.voicebook.reader.store

import kotlinx.serialization.Serializable

/** One book's reading position in the history list. */
@Serializable
data class HistoryEntry(
    val bookId: Int,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    /** Spine chapter the reader was on (0 = synthetic title page); PDF: page index. */
    val spineIndex: Int = 0,
    /** Character offset inside that chapter's paragraph text; -1 = no position yet. */
    val charOffset: Int = -1,
    /** 0..100, derived from chapter/offset at save time. */
    val progress: Int = 0,
    val updatedAt: Long = 0L,
    /** Download format of the saved book (EPUB / PDF); pins the resume download. */
    val format: String = "EPUB",
    /** Accent color extracted from the cover, packed ARGB; used for dynamic theming. */
    val seedColor: Int? = null,
)
