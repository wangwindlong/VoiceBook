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
)
