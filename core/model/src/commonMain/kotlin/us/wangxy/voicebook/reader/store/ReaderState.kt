package us.wangxy.voicebook.reader.store

import kotlinx.serialization.Serializable
import us.wangxy.voicebook.reader.api.CalibreServer

@Serializable
data class ReaderState(
    val server: CalibreServer? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** Reader body font size in sp. */
    val fontSizeSp: Int = 18,
)
