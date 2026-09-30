package us.wangxy.voicebook.server.bff.profile

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlinx.serialization.json.*
import us.wangxy.voicebook.bff.contract.*
import us.wangxy.voicebook.server.bff.content.ContentStore
import us.wangxy.voicebook.server.bff.calibre.CalibreLibrary
import us.wangxy.voicebook.server.bff.miniflux.SharedRssService

/** Profiles are rebuilt off the request path. Future embedding work belongs in rebuildAll. */
class ProfileService(private val store: ContentStore, private val library: CalibreLibrary, private val rss: SharedRssService) {
    private val profiles = ConcurrentHashMap<String, InterestProfile>()
    private val categories = ConcurrentHashMap<String, Map<String,String>>()
    private val recommendations = ConcurrentHashMap<String, ForYouResponse>()
    private val base = mapOf("read_entry" to 1.0,"star_entry" to 3.0,"comment" to 5.0,"vote" to 2.0,"open_book" to 1.0,"finish_book" to 4.0,"subscribe" to 2.0)

    suspend fun profile(owner: String): InterestProfile {
        profiles[owner]?.let { return it }
        var result=store.profile(owner)
        if (result.tags.isEmpty()) {
            val defaults=feedCategories(owner).values.distinct().filter { it.isNotBlank() }
            if(defaults.isNotEmpty()) {
                store.replaceProfile(owner,defaults.map { InterestTag(store.normalizeTag(it),0.8,"feed_category") },0)
                result=store.profile(owner)
            }
        }
        profiles[owner]=result
        return result
    }
    suspend fun feedCategories(owner: String): Map<String,String> {
        val stored=store.categories(owner).categories
        val feeds=rss.listFeeds(owner).map { it.jsonObject }
        return feeds.mapNotNull { feed ->
            val id=feed["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val category=stored[id] ?: stored[feedKey("mf:$id")] ?: feed["category"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
            category?.let { id to it }
        }.toMap().also { categories[owner]=it }
    }
    fun feedKey(value: String): String {
        val a=value.fold(-3750763034362895579L) { hash,c -> (hash xor c.code.toLong())*1099511628211L }
        val b=value.reversed().fold(7809847782465536322L) { hash,c -> (hash xor c.code.toLong())*1099511628211L }
        return a.toULong().toString(16).padStart(16,'0')+b.toULong().toString(16).padStart(16,'0')
    }
    suspend fun mute(owner: String, tag: String) { store.mute(owner,tag); profiles.remove(owner); recommendations.remove(owner) }
    suspend fun select(owner: String, tags: List<String>) { store.selectInterests(owner,tags); profiles.remove(owner); recommendations.remove(owner) }

    suspend fun rebuildAll(now: Instant = Instant.now()) {
        for(owner in store.profileOwners()) profile(owner)
        for (owner in store.dirtyOwners()) {
            try { rebuild(owner,now) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { org.slf4j.LoggerFactory.getLogger(javaClass).warn("profile rebuild failed for {}",owner,e) }
        }
        for (owner in profiles.keys) {
            try { recommendations[owner] = buildRecommendations(owner,now) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { org.slf4j.LoggerFactory.getLogger(javaClass).warn("recommendation refresh failed for {}",owner,e) }
        }
    }

    suspend fun rebuild(owner: String, now: Instant = Instant.now()) {
        val events = store.events(owner)
        val feedCategories = feedCategories(owner)
        categories[owner] = feedCategories
        val weights = mutableMapOf<String,Double>()
        val counts = mutableMapOf<String,Int>()
        val sources = mutableMapOf<String,String>()
        val bookTags = mutableMapOf<Long,List<String>>()
        val entryFeeds = mutableMapOf<String,String?>()
        for (row in events) {
            val original = row.event
            val event=if(original.objectType=="comment") {
                val resolved=store.resolveComment(owner,original.objectId) ?: continue
                original.copy(objectType=resolved.first,objectId=resolved.second)
            } else original
            val multiplier = base[event.kind] ?: continue
            val tags: List<String> = when (event.objectType) {
                "book" -> {
                    val id = event.objectId.toLongOrNull() ?: continue
                    bookTags.getOrPut(id) { try { (if (id < 0) store.book(owner,id) else library.book(id)).tags }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() } }
                }
                "feed" -> listOfNotNull(feedCategories[event.objectId])
                "entry" -> {
                    val feed = entryFeeds.getOrPut(event.objectId) {
                        try { rss.getEntry(owner,event.objectId.toLong())["feed_id"]?.jsonPrimitive?.content }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                    }
                    listOfNotNull(feed?.let(feedCategories::get))
                }
                else -> emptyList()
            }
            val days = (now.epochSecond - Instant.parse(event.ts).epochSecond).coerceAtLeast(0) / 86400.0
            for (tag in tags.map(store::normalizeTag).filter(String::isNotEmpty).distinct()) {
                weights[tag] = (weights[tag] ?: 0.0) + multiplier * 0.5.pow(days / 30.0)
                counts[tag] = (counts[tag] ?: 0) + 1
                sources[tag] = if (event.objectType == "book") "book_tag" else "feed_category"
            }
        }
        val max = weights.values.maxOrNull() ?: 1.0
        store.replaceProfile(owner, weights.map { (tag,weight) -> InterestTag(tag,weight/max,sources.getValue(tag),counts.getValue(tag)) }, events.lastOrNull()?.id ?: 0)
        profiles[owner] = store.profile(owner)
    }

    /** Only append fields to Miniflux's existing JSON envelope. */
    fun annotate(owner: String, entry: JsonObject, now: Instant = Instant.now()): JsonObject {
        val category = categories[owner]?.get(entry["feed_id"]?.jsonPrimitive?.content)
        val interest = profiles[owner]?.tags?.firstOrNull { !it.muted && it.tag == category?.let(store::normalizeTag) }
        val published = entry["published_at"]?.jsonPrimitive?.contentOrNull
        val hours = runCatching { (now.epochSecond - Instant.parse(published).epochSecond).coerceAtLeast(0)/3600.0 }.getOrDefault(0.0)
        val score = (interest?.weight ?: 0.0) * 0.5.pow(hours/72.0)
        return JsonObject(entry + mapOf("score" to JsonPrimitive(score), "reason" to (interest?.let { JsonPrimitive("来自你的「${it.tag}」兴趣") } ?: JsonNull)))
    }

    suspend fun forYou(owner: String, limit: Int): ForYouResponse {
        profile(owner)
        val cached = recommendations[owner]
        if (cached != null) return cached.copy(books=cached.books.take(limit),entries=cached.entries.take(limit))
        // Cold start remains useful; the background worker replaces this with ranked candidates.
        val books = if (library.available) library.books(null,0,limit).items else emptyList()
        return ForYouResponse(books=(store.books(owner)+books).take(limit))
    }

    private suspend fun buildRecommendations(owner: String, now: Instant): ForYouResponse {
        val interests = profile(owner).tags.filter { !it.muted && it.weight > 0 }.associateBy { it.tag }
        categories[owner] = feedCategories(owner)
        val books = store.books(owner).toMutableList()
        if (library.available) {
            var offset = 0
            do {
                val page = library.books(null,offset,100)
                books += page.items; offset += page.items.size
                if (page.items.isEmpty() || offset >= page.total) break
                kotlinx.coroutines.yield()
            } while (true)
        }
        val progress = store.history(owner,Int.MAX_VALUE).items.associateBy { it.bookId }
        val rankedBooks = books.filter { (progress[it.id]?.percent ?: 0.0) < 99 }
            .sortedByDescending { book -> book.tags.sumOf { interests[store.normalizeTag(it)]?.weight ?: 0.0 } }
        val raw = rss.listEntries(owner,"status=unread&limit=1000&order=published_at&direction=desc")["entries"]?.jsonArray.orEmpty()
        val remaining = raw.map { annotate(owner,it.jsonObject,now) }.toMutableList()
        val feeds = mutableMapOf<Long,Int>()
        val entries = mutableListOf<RecommendedEntry>()
        while (remaining.isNotEmpty() && entries.size < 200) {
            fun score(e: JsonObject): Double = (e["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / ((feeds[e["feed_id"]?.jsonPrimitive?.longOrNull] ?: 0)+1)
            val item = remaining.maxBy { score(it) }; val value = score(item); remaining.remove(item)
            val feedId = item["feed_id"]?.jsonPrimitive?.longOrNull ?: continue
            feeds[feedId] = (feeds[feedId] ?: 0)+1
            entries += RecommendedEntry(item["id"]!!.jsonPrimitive.long,feedId,item["title"]?.jsonPrimitive?.content.orEmpty(),item["url"]?.jsonPrimitive?.content.orEmpty(),item["published_at"]?.jsonPrimitive?.content,value,item["reason"]?.jsonPrimitive?.contentOrNull)
        }
        val topics = interests.values.map { interest -> RecommendedTopic(interest.tag,interest.weight,raw.count { item ->
            categories[owner]?.get(item.jsonObject["feed_id"]?.jsonPrimitive?.content)?.let(store::normalizeTag) == interest.tag
        }) }
        return ForYouResponse(rankedBooks.take(200),entries,topics)
    }
}
