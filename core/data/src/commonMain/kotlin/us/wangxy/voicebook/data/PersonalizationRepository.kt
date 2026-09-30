package us.wangxy.voicebook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.wangxy.voicebook.bff.BffSession
import us.wangxy.voicebook.bff.ContentApi
import us.wangxy.voicebook.bff.contract.BehaviorEvent

class PersonalizationRepository(private val library: LocalLibrary, private val api: ContentApi, private val session: BffSession) {
    private val enabledFlow = MutableStateFlow(false)
    val enabled = enabledFlow.asStateFlow()
    val user = session.signedInUser
    suspend fun load() { enabledFlow.value = library.settings.get("personalization.enabled") != "false" }
    suspend fun setEnabled(enabled: Boolean) { library.settings.put("personalization.enabled",enabled.toString()); enabledFlow.value=enabled }
    suspend fun recommendations(): List<CachedBook> {
        if (!enabled.value || user.value == null) return emptyList()
        val owner = user.value
        val books = api.forYou(4).books
        if (owner != user.value || !enabled.value) return emptyList()
        return books.mapIndexed { index,b -> CachedBook(b.id.toInt(),b.title,b.authors.joinToString(" & "),
            if (b.hasCover) session.baseUrl()+us.wangxy.voicebook.bff.contract.BffRoutes.calibreCover(b.id) else "",
            b.formats.firstOrNull()?.let { us.wangxy.voicebook.bff.contract.BffRoutes.calibreFile(b.id,it) }.orEmpty(),index,
            tags=b.tags,series=b.series,publisher=b.publisher,rating=b.rating,pageCount=b.pageCount,languages=b.languages,identifiers=b.identifiers,edition=b.edition,pubdate=b.pubdate,lastModified=b.lastModified,description=b.description) }
    }
    suspend fun needsOnboarding(): Boolean = user.value?.let { library.settings.get("personalization.onboarded:$it") != "true" && api.profile().tags.none { it.source=="manual" || it.evidence>0 } } ?: false
    suspend fun completeOnboarding() { user.value?.let { library.settings.put("personalization.onboarded:$it","true") } }
    suspend fun search(query: String) {
        if (!enabled.value || user.value == null || query.isBlank()) return
        try { api.events(listOf(BehaviorEvent("search","book",query.take(512),ts=kotlin.time.Clock.System.now().toString()))) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }
}
