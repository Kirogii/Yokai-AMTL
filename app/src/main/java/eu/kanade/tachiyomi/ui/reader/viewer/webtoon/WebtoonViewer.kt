package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.TranslateStubPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslatePageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.injectLazy
import yokai.domain.ai.TranslationService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Implementation of a [BaseViewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val hasMargins: Boolean = false) : BaseViewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private var scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if (dy > config.menuThreshold || dy < -config.menuThreshold) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = f@{ event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                ViewerNavigation.NavigationRegion.MENU -> activity.toggleMenu()
                ViewerNavigation.NavigationRegion.NEXT, ViewerNavigation.NavigationRegion.RIGHT -> moveToNext()
                ViewerNavigation.NavigationRegion.PREV, ViewerNavigation.NavigationRegion.LEFT -> moveToPrevious()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.zoomPropertyChangedListener = {
            frame.enableZoomOut = it
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayForNewUser
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
        config.navigationModeInvertedListener = { activity.binding.navigationOverlay.showNavigationAgain() }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        Logger.d { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, false)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            Logger.d { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                Logger.d { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        Logger.d { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            Logger.d { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        Logger.d { "setChapters" }
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            Logger.d { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        Logger.d { "moveToPage" }
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            recycler.scrollToPosition(position)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(position)
            }
        } else {
            Logger.d { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is TranslateStubPage -> {} // Skip AI translate stub
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    override fun moveToPrevious() {
        recycler.smoothScrollBy(0, -scrollDistance)
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    override fun moveToNext() {
        recycler.smoothScrollBy(0, scrollDistance)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveToNext() else moveToPrevious()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveToPrevious() else moveToNext()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) moveToPrevious()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) moveToNext()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }

    // === AI TRANSLATION ===

    private val translatedPages = mutableSetOf<Int>()
    private var isTranslatingAll = false
    private var autoTranslateScrollObserver: RecyclerView.OnScrollListener? = null
    private var translatingChapterId: Long? = null
    private val translationService: yokai.domain.ai.TranslationService by injectLazy()

    /**
     * Starts auto-translation for the current chapter in webtoon mode.
     * Translates all currently loaded pages, then watches for new pages as you scroll.
     * Automatically stops when a ChapterTransition (next chapter boundary) is encountered.
     * To translate the next chapter, tap the button again.
     */
    fun translateAllPages() {
        if (isTranslatingAll) return
        isTranslatingAll = true

        translatingChapterId = adapter.currentChapter?.chapter?.id

        val stubView = findTranslateStubView()
        stubView?.setTranslating(true)

        // Register scroll observer to catch new pages as they load while scrolling
        if (autoTranslateScrollObserver == null) {
            autoTranslateScrollObserver = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && isTranslatingAll) {
                        scope.launch { translateNewPages(stubView) }
                    }
                }
            }
        }
        recycler.addOnScrollListener(autoTranslateScrollObserver!!)

        scope.launch {
            try {
                translateNewPages(stubView)
            } catch (e: Exception) {
                Logger.e(e) { "Webtoon translation failed" }
                stubView?.setStatus("Error: ${e.localizedMessage ?: "unknown"}", true)
                stopAutoTranslation(stubView)
            }
        }
    }

    /**
     * Finds and translates all untranslated pages that are ready, within the current chapter.
     * Stops auto-translation if we've hit a ChapterTransition (next chapter boundary).
     */
    private suspend fun translateNewPages(stubView: ReaderTranslatePageView?) {
        // Check if we've scrolled past the current chapter into a transition/next chapter
        val lastVisiblePos = layoutManager.findLastEndVisibleItemPosition()
        val visibleItems = (layoutManager.findFirstVisibleItemPosition()..lastVisiblePos)
            .mapNotNull { adapter.items.getOrNull(it) }
        val hitNextChapter = visibleItems.any { it is ChapterTransition.Next }

        if (hitNextChapter) {
            stopAutoTranslation(stubView)
            stubView?.setStatus("Reached next chapter. Tap Translate to continue.")
            return
        }

        val pages = adapter.items
            .filterIsInstance(ReaderPage::class.java)
            .filter { it !is TranslateStubPage }
            .filter { it.chapter.chapter.id == translatingChapterId }
            .filter { it.status == eu.kanade.tachiyomi.source.model.Page.State.READY }
            .filter { !translatedPages.contains(it.index) }

        if (pages.isEmpty()) return

        var newTranslations = 0
        for (page in pages) {
            if (!isTranslatingAll) break

            val pageBytes = readPageBytes(page)
            if (pageBytes == null) {
                translatedPages.add(page.index)
                continue
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                ?: run { translatedPages.add(page.index); continue }

            try {
                val result = translationService.translateImage(bitmap)
                result.fold(
                    onSuccess = { translatedBitmap ->
                        val baos = java.io.ByteArrayOutputStream()
                        translatedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        val translatedBytes = baos.toByteArray()
                        page.chapter.setTranslatedPage(page.index, translatedBytes)
                        page.stream = { java.io.ByteArrayInputStream(translatedBytes) }
                        // Reset status to trigger holder reload
                        page.status =
                            eu.kanade.tachiyomi.source.model.Page.State.LOAD_PAGE
                        page.status =
                            eu.kanade.tachiyomi.source.model.Page.State.READY
                        translatedPages.add(page.index)

                        val pos = adapter.items.indexOf(page)
                        if (pos != -1) adapter.notifyItemChanged(pos)

                        newTranslations++
                        stubView?.setStatus("Auto-translating... ${translatedPages.size} pages done")
                    },
                    onFailure = { error ->
                        Logger.e(error) { "Failed to translate page ${page.index}" }
                        translatedPages.add(page.index)
                    },
                )
            } catch (e: Exception) {
                Logger.e(e) { "Translation error for page ${page.index}" }
                translatedPages.add(page.index)
            }
        }

        // Check again for chapter transition (user may have scrolled further while translating)
        val lastPos = layoutManager.findLastEndVisibleItemPosition()
        val newVisibleItems = (layoutManager.findFirstVisibleItemPosition()..lastPos)
            .mapNotNull { adapter.items.getOrNull(it) }
        if (newVisibleItems.any { it is ChapterTransition.Next }) {
            stopAutoTranslation(stubView)
            stubView?.setStatus("Reached next chapter - $newTranslations pages translated. Tap to continue.")
        }
    }

    private fun stopAutoTranslation(stubView: ReaderTranslatePageView?) {
        isTranslatingAll = false
        translatingChapterId = null
        stubView?.setTranslating(false)
        autoTranslateScrollObserver?.let { recycler.removeOnScrollListener(it) }
    }

    private fun readPageBytes(page: ReaderPage): ByteArray? {
        try {
            page.stream?.let { streamFn ->
                val input = streamFn()
                val bytes = input.readBytes()
                input.close()
                return bytes
            }
        } catch (_: Exception) {}
        return null
    }

    private fun findTranslateStubView(): ReaderTranslatePageView? {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val holder = recycler.getChildViewHolder(child)
            if (holder is WebtoonTranslateStubHolder) {
                return (holder.itemView as? ReaderTranslatePageView)
            }
        }
        return null
    }
}

private const val RECYCLER_VIEW_CACHE_SIZE = 4
