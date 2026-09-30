package us.wangxy.voicebook.data

import kotlinx.serialization.Serializable

/**
 * One row of the local shelf cache. Mirrors the fields the OPDS /opds/new feed
 * provides so the grid can be served from disk before (or without) the network;
 * [pos] keeps the server's "newest first" ordering stable across pages.
 */
@Serializable
data class CachedBook(
    val bookId: Int,
    val title: String,
    val author: String,
    val coverUrl: String,
    val epubHref: String,
    val pos: Int,
    val seedColor: Int? = null,
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val publisher: String? = null,
    val rating: Double? = null,
    val pageCount: Int? = null,
    val languages: List<String> = emptyList(),
    val identifiers: Map<String,String> = emptyMap(),
    val edition: String? = null,
    val pubdate: String? = null,
    val lastModified: String? = null,
    val description: String? = null,

)
