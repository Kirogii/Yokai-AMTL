package eu.kanade.tachiyomi.ui.reader.model

import android.graphics.drawable.Drawable

/**
 * A special "page 0" that appears at the start of every chapter in the reader.
 * It displays a "Translate" button instead of actual manga page content.
 *
 * In pager mode: user swipes from this page to page 1; tapping the button
 *   translates the currently visible real page.
 * In webtoon mode: appears at the very top; tapping translates all visible pages.
 */
class TranslateStubPage(
    chapter: ReaderChapter,
) : ReaderPage(
    index = -1,  // always negative index so it sorts before real pages
    url = "translate://stub",
    imageUrl = null,
) {

    override var chapter: ReaderChapter = chapter
        set(value) { field = value }

    init {
        fullPage = true
        firstHalf = null
        status = State.READY
    }

    companion object {
        const val VIEW_TYPE = 2  // distinct from PAGE_VIEW (0) and TRANSITION_VIEW (1)
    }
}
