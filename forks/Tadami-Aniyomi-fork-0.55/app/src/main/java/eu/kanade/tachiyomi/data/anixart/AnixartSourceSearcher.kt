package eu.kanade.tachiyomi.data.anixart

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.anixart.AnixartMatcher
import tachiyomi.data.anixart.AnixartTitleSearcher
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/**
 * [AnixartTitleSearcher] implementation backed by installed anime sources.
 *
 * Anixart exports have no source/url, so we run the title query against a
 * user-selected set of catalogue sources and turn each [eu.kanade.tachiyomi.animesource.model.SAnime]
 * into a [AnixartMatcher.SearchCandidate] for the pure matcher to score.
 *
 * Only the first page of each source is taken — for title matching the top
 * results are what matter and we must not hammer sources for hundreds of rows.
 */
class AnixartSourceSearcher(
    private val sourceManager: AnimeSourceManager,
    private val sourceIds: List<Long>,
) : AnixartTitleSearcher {

    override suspend fun search(query: String): List<AnixartMatcher.SearchCandidate> {
        if (query.isBlank()) return emptyList()
        val results = ArrayList<AnixartMatcher.SearchCandidate>()
        for (sourceId in sourceIds) {
            val source = sourceManager.get(sourceId) as? AnimeCatalogueSource ?: continue
            val page = try {
                source.getSearchAnime(1, query, AnimeFilterList())
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anixart search failed on source $sourceId for '$query'" }
                continue
            }
            for (sAnime in page.animes) {
                val titles = buildList {
                    add(sAnime.title)
                    // Some sources pack alternative titles into the description; keep title only
                    // to avoid noise, alternatives can be added later if sources expose them.
                }.filter { it.isNotBlank() }
                results += AnixartMatcher.SearchCandidate(
                    // No stable local id pre-persist; use a hash of source+url.
                    id = (sourceId.toString() + sAnime.url).hashCode().toLong(),
                    sourceId = sourceId,
                    displayTitle = sAnime.title,
                    titles = titles,
                    url = sAnime.url,
                    thumbnailUrl = sAnime.thumbnail_url,
                )
            }
        }
        return results
    }
}
