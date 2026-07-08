package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.SplitPage
import eu.kanade.tachiyomi.ui.reader.model.TranslateStubPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.JoinedReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslatePageView
import eu.kanade.tachiyomi.ui.reader.viewer.hasMissingChapters
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 *
 * Modified to support TranslateStubPage as "page 0".
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    var joinedItems: MutableList<Pair<ReaderItem, ReaderItem?>> = mutableListOf()
        private set

    private var subItems: MutableList<ReaderItem> = mutableListOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var pageToShift: ReaderPage? = null

    private var shifted = viewer.config.shiftDoublePage
    private var doubledUp = viewer.config.doublePages

    var currentChapter: ReaderChapter? = null
    var forceTransition = false

    /** Reference to the translate stub page for the current chapter */
    var translateStubPage: TranslateStubPage? = null
        private set

    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<ReaderItem>()

        val prevHasMissingChapters = hasMissingChapters(chapters.currChapter, chapters.prevChapter)
        val nextHasMissingChapters = hasMissingChapters(chapters.nextChapter, chapters.currChapter)

        this.forceTransition = forceTransition

        // Add previous chapter pages and transition.
        if (chapters.prevChapter != null) {
            val prevPages = chapters.prevChapter.pages
            val numberOfFullPages =
                chapters.prevChapter.pages?.count { it.fullPage == true || it.isolatedPage } ?: 0
            if (prevPages != null) {
                newItems.addAll(prevPages.takeLast(if ((prevPages.size + numberOfFullPages) % 2 == 0) 2 else 3))
            }
        }

        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // === AI TRANSLATION: Insert TranslateStubPage at the start of the current chapter ===
        val stubPage = TranslateStubPage(chapters.currChapter)
        this.translateStubPage = stubPage
        newItems.add(stubPage)

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            newItems.addAll(currPages)
        }

        currentChapter = chapters.currChapter

        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (nextHasMissingChapters || forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        if (chapters.nextChapter != null) {
            val nextPages = chapters.nextChapter.pages
            if (nextPages != null) {
                newItems.addAll(nextPages.take(2))
            }
        }

        subItems = newItems.toMutableList()

        var useSecondPage = false
        if (shifted != viewer.config.shiftDoublePage || (doubledUp != viewer.config.doublePages && doubledUp)) {
            if (shifted && doubledUp == viewer.config.doublePages) {
                useSecondPage = true
            }
            shifted = viewer.config.shiftDoublePage
        }
        doubledUp = viewer.config.doublePages
        setJoinedItems(useSecondPage)
    }

    override fun getCount(): Int = joinedItems.size

    override fun createView(container: ViewGroup, position: Int): View {
        val item = joinedItems[position].first
        val item2 = joinedItems[position].second
        return when (item) {
            is TranslateStubPage -> {
                // Create the translate button view for the stub page
                val view = ReaderTranslatePageView(viewer.activity)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                view.onTranslateClicked = {
                    viewer.translateAllPages()
                }
                (view as View)
            }
            is ReaderPage -> PagerPageHolder(viewer, item, item2 as? ReaderPage)
            is ChapterTransition -> PagerTransitionHolder(viewer, item)
            else -> throw UnsupportedOperationException("${item::class.qualifiedName ?: "anonymous"} is not supported!")
        }
    }

    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = joinedItems.indexOfFirst {
                if (it.first is InsertPage && view.item is Pair<*, *>) {
                    ((view.item as? Pair<*, *>?)?.first as? InsertPage)?.let { viewPage ->
                        return@indexOfFirst (it.first as? InsertPage)?.isFromSamePage(viewPage) == true &&
                            (it.first as? InsertPage)?.firstHalf == viewPage.firstHalf
                    }
                }
                val secondPage = it.second as? ReaderPage
                view.item == it.first to secondPage
            }
            if (position != -1) return position
            else Logger.d { "Position for ${view.item} not found" }
        }
        return POSITION_NONE
    }

    fun splitDoublePages(current: ReaderPage) {
        val oldCurrent = joinedItems.getOrNull(viewer.pager.currentItem)
        setJoinedItems(
            if (viewer.config.splitPages) {
                (oldCurrent?.first as? ReaderPage)?.firstHalf == false
            } else {
                oldCurrent?.second == current ||
                    (current.index + 1) <
                    (((oldCurrent?.second ?: oldCurrent?.first) as? ReaderPage)?.index ?: 0)
            },
        )

        viewer.scope.launchUI {
            delay(100)
            viewer.onPageChange(viewer.pager.currentItem)
        }
    }

    private fun flushJoinBuffer(
        buffer: MutableList<ReaderPage>,
        joined: MutableList<Pair<ReaderItem, ReaderItem?>>,
    ) {
        if (buffer.isEmpty()) return
        val grouped = groupPagesForDoublePage(
            buffer.toList(),
            joinDoublePages = true,
            shiftDoublePages = viewer.config.shiftDoublePage,
            isLandscape = true,
            isR2L = viewer is R2LPagerViewer,
        )
        for (item in grouped) {
            when (item) {
                is JoinedReaderPage -> joined.add(Pair(item.firstPage, item.secondPage))
                is ReaderPage -> joined.add(Pair(item, null))
            }
        }
        buffer.clear()
    }

    private fun setJoinedItems(useSecondPage: Boolean = false) {
        val oldCurrent = joinedItems.getOrNull(viewer.pager.currentItem)

        // joinDoublePages: pair non-wide pages side-by-side in landscape
        if (viewer.config.joinDoublePages) {
            val isLandscape = (viewer.activity.resources.configuration?.orientation
                ?: Configuration.ORIENTATION_PORTRAIT) == Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape) {
                val newJoined = mutableListOf<Pair<ReaderItem, ReaderItem?>>()
                var pageBuffer = mutableListOf<ReaderPage>()

                for (item in subItems) {
                    when {
                        item is TranslateStubPage -> {
                            flushJoinBuffer(pageBuffer, newJoined)
                            newJoined.add(Pair(item, null))
                        }
                        item is ChapterTransition -> {
                            flushJoinBuffer(pageBuffer, newJoined)
                            newJoined.add(Pair(item, null))
                        }
                        item is ReaderPage && item !is TranslateStubPage -> {
                            pageBuffer.add(item)
                        }
                    }
                }
                flushJoinBuffer(pageBuffer, newJoined)

                if (viewer is R2LPagerViewer) {
                    newJoined.reverse()
                }
                this.joinedItems = newJoined
                notifyDataSetChanged()

                val newPage = oldCurrent?.first ?: return
                val index = joinedItems.indexOfFirst {
                    val firstPage = it.first as? ReaderPage
                    val secondPage = it.second as? ReaderPage
                    it.first == newPage || it.second == newPage ||
                        (firstPage != null && (firstPage.isFromSamePage(newPage as? ReaderPage ?: return@indexOfFirst false)))
                }
                if (index > -1) {
                    viewer.pager.setCurrentItem(index, false)
                }
                return
            }
        }

        if (!viewer.config.doublePages) {
            subItems.forEach {
                (it as? ReaderPage)?.apply {
                    shiftedPage = false
                    firstHalf = null
                    endPageConfidence = null
                    startPageConfidence = null
                }
            }
            if (viewer.config.splitPages) {
                val pagedItems = mutableListOf<ReaderItem>()
                subItems.forEach { page ->
                    if (page is ReaderPage && page.longPage == true) {
                        page.firstHalf = true
                        pagedItems.add(InsertPage(page).apply { firstHalf = true })
                        pagedItems.add(InsertPage(page))
                        return@forEach
                    }
                    pagedItems.add(page)
                }
                this.joinedItems = pagedItems.map {
                    Pair<ReaderItem, ReaderItem?>(
                        it,
                        if ((it as? ReaderPage)?.fullPage == true && it.firstHalf == true) SplitPage else null,
                    )
                }.toMutableList()
            } else {
                this.joinedItems = subItems.map { Pair<ReaderItem, ReaderItem?>(it, null) }.toMutableList()
            }
            if (viewer is R2LPagerViewer) {
                joinedItems.reverse()
            }
        } else {
            val pagedItems = mutableListOf<MutableList<ReaderPage?>>()
            val otherItems = mutableListOf<ReaderItem>()
            pagedItems.add(mutableListOf())

            // Collect all non-page items (transitions, stub) separately to preserve order
            val processedIndices = mutableSetOf<Int>()
            subItems.forEachIndexed { idx, it ->
                if (it is ReaderPage && it !is TranslateStubPage) {
                    if (pagedItems.last().lastOrNull() != null &&
                        pagedItems.last().last()?.chapter?.chapter?.id != it.chapter.chapter.id
                    ) {
                        pagedItems.add(mutableListOf())
                    }
                    pagedItems.last().add(it)
                    processedIndices.add(idx)
                } else if (it !is ReaderPage || it is TranslateStubPage) {
                    otherItems.add(it)
                    processedIndices.add(idx)
                }
            }

            var pagedIndex = 0
            val subJoinedItems = mutableListOf<Pair<ReaderItem, ReaderItem?>>()

            // Key: also handle TranslateStubPage items and non-page items
            val allSegments = mutableListOf<Pair<MutableList<ReaderPage?>, Int>>()
            var segIndex = 0
            while (segIndex < subItems.size) {
                val item = subItems[segIndex]
                if (item is TranslateStubPage || item is ChapterTransition) {
                    allSegments.add(Pair(mutableListOf(), segIndex))
                    segIndex++
                    continue
                }
                if (item is ReaderPage) {
                    val pages = mutableListOf<ReaderPage?>()
                    pages.add(item)
                    segIndex++
                    while (segIndex < subItems.size && subItems[segIndex] is ReaderPage &&
                        subItems[segIndex] !is TranslateStubPage &&
                        (subItems[segIndex] as ReaderPage).chapter.chapter.id == item.chapter.chapter.id
                    ) {
                        pages.add(subItems[segIndex] as ReaderPage)
                        segIndex++
                    }
                    allSegments.add(Pair(pages, segIndex - pages.size))
                } else {
                    segIndex++
                }
            }

            subJoinedItems.clear()
            var otherIdx = 0
            var segmentCursor = 0

            while (segmentCursor < subItems.size) {
                val item = subItems[segmentCursor]
                when {
                    item is TranslateStubPage -> {
                        subJoinedItems.add(Pair(item, null))
                        segmentCursor++
                    }
                    item is ChapterTransition -> {
                        // Find if this transition is in pagedItems by matching the chapter
                        subJoinedItems.add(Pair(item, null))
                        segmentCursor++
                    }
                    item is ReaderPage -> {
                        // Collect pages until a non-page or different-chapter item
                        val pages = mutableListOf<ReaderPage?>()
                        pages.add(item)
                        segmentCursor++
                        while (segmentCursor < subItems.size &&
                            subItems[segmentCursor] is ReaderPage &&
                            subItems[segmentCursor] !is TranslateStubPage
                        ) {
                            pages.add(subItems[segmentCursor] as ReaderPage)
                            segmentCursor++
                        }

                        // Handle shifting
                        if (viewer.config.shiftDoublePage) {
                            var index = pages.indexOf(pageToShift)
                            if (pageToShift?.fullPage == true) {
                                index = max(0, index - 1)
                            }
                            val fullPageBeforeIndex = max(
                                0,
                                if (index > -1) pages.take(index).indexOfLast { it?.fullPage == true } else -1,
                            )
                            (fullPageBeforeIndex until pages.size).forEach {
                                if (pages[it]?.fullPage != true) {
                                    pages[it]?.shiftedPage = true
                                    return@forEach
                                }
                            }
                        }

                        // Add blanks for chunking
                        var itemIndex = 0
                        while (itemIndex < pages.size) {
                            pages[itemIndex]?.isolatedPage = false
                            if (pages[itemIndex]?.fullPage == true || pages[itemIndex]?.shiftedPage == true) {
                                pages.add(itemIndex + 1, null)
                                if (pages[itemIndex]?.fullPage == true && itemIndex > 0 &&
                                    pages[itemIndex - 1] != null && (itemIndex - 1) % 2 == 0
                                ) {
                                    pages[itemIndex - 1]?.isolatedPage = true
                                    pages.add(itemIndex, null)
                                    itemIndex++
                                }
                                itemIndex++
                            }
                            itemIndex++
                        }

                        // Chunk em
                        if (pages.isNotEmpty()) {
                            subJoinedItems.addAll(
                                pages.chunked(2).map { Pair(it.first()!!, it.getOrNull(1)) },
                            )
                        }
                    }
                }
            }

            if (viewer is R2LPagerViewer) {
                subJoinedItems.reverse()
            }
            this.joinedItems = subJoinedItems
        }
        notifyDataSetChanged()

        val newPage =
            when {
                (oldCurrent?.first as? ReaderPage)?.chapter != currentChapter &&
                    (oldCurrent?.first as? ChapterTransition)?.from != currentChapter -> subItems.find { (it as? ReaderPage)?.chapter == currentChapter }
                useSecondPage && oldCurrent?.second is ReaderPage -> (oldCurrent.second ?: oldCurrent.first)
                else -> oldCurrent?.first ?: return
            }
        var index = joinedItems.indexOfFirst {
            val readerPage = it.first as? ReaderPage
            val readerPage2 = it.second as? ReaderPage
            val newReaderPage = newPage as? ReaderPage
            it.first == newPage || it.second == newPage ||
                (
                    readerPage != null && newReaderPage != null &&
                        (readerPage.isFromSamePage(newReaderPage) ||
                            readerPage2?.isFromSamePage(newReaderPage) == true) &&
                        (readerPage.firstHalf == !useSecondPage || readerPage.firstHalf == null)
                    )
        }
        if (newPage is ChapterTransition && index == -1 && !forceTransition) {
            val newerPage = if (newPage is ChapterTransition.Next) {
                joinedItems.filter {
                    (it.first as? ReaderPage)?.chapter == newPage.to
                }.minByOrNull { (it.first as? ReaderPage)?.index ?: Int.MAX_VALUE }?.first
            } else {
                joinedItems.filter {
                    (it.first as? ReaderPage)?.chapter == newPage.to
                }.maxByOrNull { (it.first as? ReaderPage)?.index ?: Int.MIN_VALUE }?.first
            }
            index = joinedItems.indexOfFirst { it.first == newerPage || it.second == newerPage }
        }
        if (index > -1) {
            viewer.pager.setCurrentItem(index, false)
        }
    }
}

internal fun groupPagesForDoublePage(
    pages: List<ReaderPage>,
    joinDoublePages: Boolean,
    shiftDoublePages: Boolean = false,
    isLandscape: Boolean,
    isR2L: Boolean,
): List<Any> {
    if (!joinDoublePages || !isLandscape) {
        return pages
    }

    val result = mutableListOf<Any>()
    var i = 0
    if (shiftDoublePages && pages.isNotEmpty()) {
        result.add(pages[0])
        i = 1
    }
    while (i < pages.size) {
        val currentPage = pages[i]
        if (currentPage.isWide) {
            result.add(currentPage)
            i++
            continue
        }

        val nextPage = pages.getOrNull(i + 1)
        if (nextPage != null && !nextPage.isWide) {
            val first = if (isR2L) nextPage else currentPage
            val second = if (isR2L) currentPage else nextPage
            result.add(JoinedReaderPage(first, second))
            i += 2
        } else {
            result.add(currentPage)
            i++
        }
    }
    return result
}
