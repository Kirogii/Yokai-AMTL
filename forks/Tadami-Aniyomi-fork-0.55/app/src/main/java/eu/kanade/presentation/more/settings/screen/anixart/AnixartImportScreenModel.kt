package eu.kanade.presentation.more.settings.screen.anixart

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.anixart.AnixartSourceSearcher
import eu.kanade.tachiyomi.data.anixart.ImportAnixartEntries
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.data.anixart.AnixartCsvParser
import tachiyomi.data.anixart.AnixartImportPlanner
import tachiyomi.data.anixart.AnixartMatcher
import tachiyomi.data.anixart.AnixartRow
import tachiyomi.data.anixart.AnixartStatus
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * Drives the Anixart import wizard as a small state machine:
 *
 *   PickSources -> (match) -> Review -> (import) -> Done
 *
 * The CSV is parsed up-front; matching and importing reuse the pure data-layer
 * components ([AnixartCsvParser], [AnixartMatcher], [AnixartImportPlanner]) and
 * the app-layer IO ([AnixartSourceSearcher], [ImportAnixartEntries]).
 */
class AnixartImportScreenModel(
    private val openStream: () -> InputStream,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val importEntries: ImportAnixartEntries = Injekt.get(),
) : StateScreenModel<AnixartImportScreenModel.State>(State.Loading) {

    @Immutable
    data class ReviewItem(
        val row: AnixartRow,
        val result: AnixartMatcher.MatchResult,
        val selectedId: Long?,
        val enabled: Boolean,
    )

    sealed interface State {
        data object Loading : State
        data class Error(val messageKey: ErrorKind) : State
        data class PickSources(
            val rows: List<AnixartRow>,
            val sources: List<SourceChoice>,
            val categories: List<Category>,
            val statusCategoryIds: Map<AnixartStatus, Long?>,
            val favoriteCategoryId: Long?,
        ) : State
        data object Matching : State
        data class Review(
            val items: List<ReviewItem>,
            val statusCategoryIds: Map<AnixartStatus, Long?>,
            val favoriteCategoryId: Long?,
        ) : State
        data class Importing(val current: Int, val total: Int) : State
        data class Done(val report: ImportAnixartEntries.Report) : State
    }

    enum class ErrorKind { INVALID, EMPTY }

    data class SourceChoice(val id: Long, val name: String, val selected: Boolean)

    init {
        load()
    }

    private fun load() {
        screenModelScope.launch {
            try {
                val rows = openStream().use { AnixartCsvParser.parse(it) }
                if (rows.isEmpty()) {
                    mutableState.update { State.Error(ErrorKind.EMPTY) }
                    return@launch
                }
                val categories = getAnimeCategories.await()
                val sources = sourceManager.getCatalogueSources()
                    .map { SourceChoice(it.id, it.name, selected = false) }
                mutableState.update {
                    State.PickSources(
                        rows = rows,
                        sources = sources,
                        categories = categories,
                        statusCategoryIds = emptyMap(),
                        favoriteCategoryId = null,
                    )
                }
            } catch (e: AnixartCsvParser.InvalidAnixartCsvException) {
                mutableState.update { State.Error(ErrorKind.INVALID) }
            }
        }
    }

    fun toggleSource(id: Long) {
        mutableState.update { s ->
            if (s !is State.PickSources) return@update s
            s.copy(sources = s.sources.map { if (it.id == id) it.copy(selected = !it.selected) else it })
        }
    }

    fun setCategoryMapping(status: AnixartStatus, categoryId: Long?) {
        mutableState.update { s ->
            if (s !is State.PickSources) return@update s
            val updated = s.statusCategoryIds.toMutableMap()
            if (categoryId == null) {
                updated.remove(status)
            } else {
                updated[status] = categoryId
            }
            s.copy(statusCategoryIds = updated)
        }
    }

    fun setFavoriteCategoryMapping(categoryId: Long?) {
        mutableState.update { s ->
            if (s !is State.PickSources) return@update s
            s.copy(favoriteCategoryId = categoryId)
        }
    }

    fun startMatching() {
        val current = state.value as? State.PickSources ?: return
        val sourceIds = current.sources.filter { it.selected }.map { it.id }
        if (sourceIds.isEmpty()) return
        val statusCategoryIds = current.statusCategoryIds
        val favoriteCategoryId = current.favoriteCategoryId
        mutableState.update { State.Matching }
        screenModelScope.launch {
            val searcher = AnixartSourceSearcher(sourceManager, sourceIds)
            val items = current.rows.map { row ->
                val queries = row.candidateTitles()
                val candidates = queries.flatMap { searcher.search(it) }.distinctBy { it.sourceId to it.url }
                val result = AnixartMatcher.match(queries, candidates)
                ReviewItem(
                    row = row,
                    result = result,
                    selectedId = result.best?.candidate?.id,
                    enabled = result.confidence != AnixartMatcher.Confidence.NO_MATCH,
                )
            }
            mutableState.update { State.Review(items, statusCategoryIds, favoriteCategoryId) }
        }
    }

    fun setSelection(rowIndex: Int, candidateId: Long?) {
        mutableState.update { s ->
            if (s !is State.Review) return@update s
            s.copy(items = s.items.mapIndexed { i, it -> if (i == rowIndex) it.copy(selectedId = candidateId) else it })
        }
    }

    fun setEnabled(rowIndex: Int, enabled: Boolean) {
        mutableState.update { s ->
            if (s !is State.Review) return@update s
            s.copy(items = s.items.mapIndexed { i, it -> if (i == rowIndex) it.copy(enabled = enabled) else it })
        }
    }

    fun selectedCount(): Int =
        (state.value as? State.Review)?.items?.count { it.enabled && it.selectedId != null } ?: 0

    fun startImport() {
        val review = state.value as? State.Review ?: return
        val selections = review.items.map { item ->
            val chosen = item.result.ranked.firstOrNull { it.candidate.id == item.selectedId }?.candidate
            AnixartImportPlanner.Selection(item.row, chosen, item.enabled)
        }
        val statusMap = review.statusCategoryIds.filterValues { it != null }.mapValues { it.value!! }
        val config = AnixartImportPlanner.Config(
            statusCategoryIds = statusMap,
            favoriteCategoryId = review.favoriteCategoryId,
        )
        val plan = AnixartImportPlanner.plan(selections, config)
        mutableState.update { State.Importing(0, plan.actions.size) }
        screenModelScope.launch {
            val report = importEntries.await(plan) { current, total ->
                mutableState.update { State.Importing(current, total) }
            }
            mutableState.update { State.Done(report) }
        }
    }
}
