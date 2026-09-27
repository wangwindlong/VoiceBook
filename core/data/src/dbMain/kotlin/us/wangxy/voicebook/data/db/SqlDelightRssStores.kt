package us.wangxy.voicebook.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.data.rss.RssAccountStore
import us.wangxy.voicebook.data.rss.RssFeedStore
import us.wangxy.voicebook.data.rss.RssPostStore
import us.wangxy.voicebook.data.rss.RssPostsQuery
import us.wangxy.voicebook.db.RssAccount
import us.wangxy.voicebook.db.RssFeed
import us.wangxy.voicebook.db.RssPost
import us.wangxy.voicebook.db.SelectLatestWithImage
import us.wangxy.voicebook.db.VoiceBookDatabase
import us.wangxy.voicebook.rss.RssAccountModel
import us.wangxy.voicebook.rss.RssFeedModel
import us.wangxy.voicebook.rss.RssPostModel
import us.wangxy.voicebook.rss.RssSyncMode

/** SQLDelight-backed RSS stores (android/ios/jvm). */
internal class SqlDelightRssStores(private val db: VoiceBookDatabase) {
    val feeds: RssFeedStore = Feeds()
    val posts: RssPostStore = Posts()
    val account: RssAccountStore = Account()

    private inner class Feeds : RssFeedStore {

        override suspend fun upsert(feed: RssFeedModel) = withContext(Dispatchers.IO) {
            db.rssFeedQueries.upsertFeed(
                id = feed.id,
                title = feed.title,
                link = feed.link,
                siteUrl = feed.siteUrl,
                feedUrl = feed.feedUrl,
                imageUrl = feed.imageUrl,
                lastSyncedAt = feed.lastSyncedAt,
                seedColor = feed.seedColor?.toLong(),
            )
            Unit
        }

        override suspend fun all(): List<RssFeedModel> = withContext(Dispatchers.IO) {
            db.rssFeedQueries.selectFeeds().executeAsList().map { it.toModel() }
        }

        override suspend fun get(id: String): RssFeedModel? = withContext(Dispatchers.IO) {
            db.rssFeedQueries.selectFeed(id).executeAsOneOrNull()?.toModel()
        }

        override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
            db.rssPostQueries.deletePostsForFeed(feedId = id)
            db.rssFeedQueries.deleteFeed(id)
            Unit
        }

        override suspend fun clearAll() = withContext(Dispatchers.IO) {
            db.rssPostQueries.deleteAllPosts()
            db.rssFeedQueries.deleteAllFeeds()
            Unit
        }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) = withContext(Dispatchers.IO) {
            db.rssFeedQueries.updateFeedSeedColor(id = id, seedColor = seedColor?.toLong())
            Unit
        }

        private fun RssFeed.toModel() = RssFeedModel(
            id = id,
            title = title,
            link = link,
            siteUrl = siteUrl,
            feedUrl = feedUrl,
            imageUrl = imageUrl,
            lastSyncedAt = lastSyncedAt,
            seedColor = seedColor?.toInt(),
        )
    }

    private inner class Posts : RssPostStore {

        override fun observeLatest(): Flow<List<RssPostModel>> =
            db.rssPostQueries.selectPostsPage(
                feedId = null,
                unreadOnly = 0L,
                starredOnly = 0L,
                limit = LATEST_FLOW_SIZE,
                offset = 0,
            ).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toModel() } }

        override suspend fun upsertAll(posts: List<RssPostModel>) = withContext(Dispatchers.IO) {
            db.rssPostQueries.transaction {
                posts.forEach { post ->
                    db.rssPostQueries.insertOrIgnorePost(
                        id = post.id,
                        feedId = post.feedId,
                        title = post.title,
                        link = post.link,
                        publishedAt = post.publishedAt,
                        summary = post.summary,
                        contentHtml = post.contentHtml,
                        imageUrl = post.imageUrl,
                        audioUrl = post.audioUrl,
                    )
                    db.rssPostQueries.refreshPostMeta(
                        post.title,
                        post.publishedAt,
                        post.summary,
                        post.contentHtml,
                        post.imageUrl,
                        post.audioUrl,
                        post.id,
                    )
                }
            }
            Unit
        }

        override suspend fun page(limit: Int, offset: Int, query: RssPostsQuery): List<RssPostModel> =
            withContext(Dispatchers.IO) {
                db.rssPostQueries.selectPostsPage(
                    feedId = query.feedId,
                    unreadOnly = if (query.unreadOnly) 1L else 0L,
                    starredOnly = if (query.starredOnly) 1L else 0L,
                    limit = limit.toLong(),
                    offset = offset.toLong(),
                ).executeAsList().map { it.toModel() }
            }

        override suspend fun count(query: RssPostsQuery): Int = withContext(Dispatchers.IO) {
            db.rssPostQueries.countPosts(
                feedId = query.feedId,
                unreadOnly = if (query.unreadOnly) 1L else 0L,
                starredOnly = if (query.starredOnly) 1L else 0L,
            ).executeAsOne().toInt()
        }

        override suspend fun get(id: String): RssPostModel? = withContext(Dispatchers.IO) {
            db.rssPostQueries.selectPost(id).executeAsOneOrNull()?.toModel()
        }

        override suspend fun setRead(ids: List<String>, read: Boolean) = withContext(Dispatchers.IO) {
            db.rssPostQueries.markPostsRead(ids = ids, read = if (read) 1L else 0L)
            Unit
        }

        override suspend fun markAllRead() = withContext(Dispatchers.IO) {
            db.rssPostQueries.markAllPostsRead()
            Unit
        }

        override suspend fun setStarred(id: String, starred: Boolean) = withContext(Dispatchers.IO) {
            db.rssPostQueries.setPostStarred(id = id, starred = if (starred) 1L else 0L)
            Unit
        }

        override suspend fun setAudioProgress(id: String, positionMs: Long, durationMs: Long) =
            withContext(Dispatchers.IO) {
                db.rssPostQueries.setPostAudioProgress(id = id, positionMs = positionMs, durationMs = durationMs)
                Unit
            }

        override suspend fun updateSeedColor(id: String, seedColor: Int?) = withContext(Dispatchers.IO) {
            db.rssPostQueries.updatePostSeedColor(id = id, seedColor = seedColor?.toLong())
            Unit
        }

        override suspend fun postsNeedingSeedColor(limit: Int): List<RssPostModel> =
            withContext(Dispatchers.IO) {
                db.rssPostQueries.selectLatestWithImage(limit = limit.toLong()).executeAsList().map { it.toModel() }
            }

        override suspend fun search(text: String, limit: Int, offset: Int): List<RssPostModel> =
            withContext(Dispatchers.IO) {
                val needle = text.trim()
                if (needle.length < 3) {
                    // Trigram tokenizer needs >= 3 chars for MATCH; shorter queries fall back to LIKE.
                    db.rssPostQueries.searchPostsLike(
                        pattern = "%$needle%",
                        limit = limit.toLong(),
                        offset = offset.toLong(),
                    ).executeAsList().map { it.toModel() }
                } else {
                    // Quote as a phrase so user punctuation isn't parsed as FTS5 syntax.
                    db.rssPostFtsQueries.searchPosts(
                        query = "\u0022$needle\u0022",
                        limit = limit.toLong(),
                        offset = offset.toLong(),
                    ).executeAsList().map { it.toModel() }
                }
            }

        override suspend fun deleteReadBefore(thresholdMs: Long) = withContext(Dispatchers.IO) {
            db.rssPostQueries.deleteReadPostsBefore(threshold = thresholdMs)
            Unit
        }

        override suspend fun deleteForFeed(feedId: String) = withContext(Dispatchers.IO) {
            db.rssPostQueries.deletePostsForFeed(feedId = feedId)
            Unit
        }

        override suspend fun clear() = withContext(Dispatchers.IO) {
            db.rssPostQueries.deleteAllPosts()
            Unit
        }

        private fun SelectLatestWithImage.toModel() = RssPostModel(
            id = id,
            feedId = feedId,
            title = title,
            link = link,
            publishedAt = publishedAt,
            summary = summary,
            contentHtml = contentHtml,
            imageUrl = imageUrl,
            read = read == 1L,
            starred = starred == 1L,
            audioUrl = audioUrl,
            audioPositionMs = audioPositionMs,
            audioDurationMs = audioDurationMs,
            seedColor = seedColor?.toInt(),
        )

        private fun RssPost.toModel() = RssPostModel(
            id = id,
            feedId = feedId,
            title = title,
            link = link,
            publishedAt = publishedAt,
            summary = summary,
            contentHtml = contentHtml,
            imageUrl = imageUrl,
            read = read == 1L,
            starred = starred == 1L,
            audioUrl = audioUrl,
            audioPositionMs = audioPositionMs,
            audioDurationMs = audioDurationMs,
            seedColor = seedColor?.toInt(),
        )
    }

    private inner class Account : RssAccountStore {

        override suspend fun get(): RssAccountModel? = withContext(Dispatchers.IO) {
            db.rssAccountQueries.selectAccount(Id)
                .executeAsOneOrNull()
                ?.toModel()
        }

        override suspend fun set(account: RssAccountModel?) = withContext(Dispatchers.IO) {
            if (account == null) {
                // No delete query; overwrite with a Local account instead.
                db.rssAccountQueries.upsertAccount(
                    id = Id,
                    mode = RssSyncMode.Local.name,
                    serverUrl = null,
                    token = null,
                    lastEntryId = null,
                    lastSyncedAt = 0L,
                )
            } else {
                db.rssAccountQueries.upsertAccount(
                    id = Id,
                    mode = account.mode.name,
                    serverUrl = account.serverUrl,
                    token = account.token,
                    lastEntryId = account.lastEntryId,
                    lastSyncedAt = account.lastSyncedAt,
                )
            }
            Unit
        }

        private fun RssAccount.toModel() = RssAccountModel(
            mode = RssSyncMode.valueOf(mode),
            serverUrl = serverUrl,
            token = token,
            lastEntryId = lastEntryId,
            lastSyncedAt = lastSyncedAt,
        )

    }

    private companion object {
        const val Id = 1L
        const val LATEST_FLOW_SIZE = 50L
    }
}
