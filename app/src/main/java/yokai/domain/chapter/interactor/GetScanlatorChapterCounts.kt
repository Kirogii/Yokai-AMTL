package yokai.domain.chapter.interactor

import yokai.domain.chapter.ChapterRepository

class GetScanlatorChapterCounts(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(mangaId: Long): Map<String, Int> {
        val chapters = chapterRepository.getChapters(mangaId, false)
        return chapters
            .groupBy { it.scanlator ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotBlank() }
    }
}
