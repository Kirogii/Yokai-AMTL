package eu.kanade.tachiyomi.ui.manga

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.transition.TransitionSet
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.databinding.ChapterHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaHeaderItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.nameBasedOnEnabledLanguages
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.view.resetStrokeColor
import yokai.i18n.MR
import yokai.presentation.core.util.coil.loadManga
import yokai.util.lang.getString
import android.R as AR

@SuppressLint("ClickableViewAccessibility")
class MangaHeaderHolder(
    view: View,
    private val adapter: MangaDetailsAdapter,
    startExpanded: Boolean,
    private val isTablet: Boolean = false,
) : BaseFlexibleViewHolder(view, adapter) {

    val binding: MangaHeaderItemBinding? = try {
        MangaHeaderItemBinding.bind(view)
    } catch (e: Exception) { null }
    private val chapterBinding: ChapterHeaderItemBinding? = try {
        ChapterHeaderItemBinding.bind(view)
    } catch (e: Exception) { null }

    private var showReadingButton = true
    private var showMoreButton = true
    var hadSelection = false
    private var canCollapse = true

    init {
        if (binding == null) {
            chapterBinding?.chapterLayout?.setOnClickListener {
                adapter.delegate.showChapterFilter() }
        } else with(binding!!) {
            startReadingButton.transitionName = "details start reading transition"
            chapterLayout.setOnClickListener { adapter.delegate.showChapterFilter() }
            startReadingButton.setOnClickListener { adapter.delegate.readNextChapter(it) }
            topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = adapter.delegate.topCoverHeight() }

            moreButton.setOnClickListener { expandDesc(true) }
            mangaSummary.setOnClickListener {
                if (moreButton.isVisible) expandDesc(true)
                else if (!hadSelection) collapseDesc(true)
                else hadSelection = false }
            mangaSummary.setOnLongClickListener {
                if (mangaSummary.isTextSelectable && !adapter.recyclerView.canScrollVertically(-1))
                    (adapter.delegate as MangaDetailsController).binding.swipeRefresh.isEnabled = false
                false }
            mangaSummary.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) view.requestFocus()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    hadSelection = mangaSummary.hasSelection()
                    (adapter.delegate as MangaDetailsController).binding.swipeRefresh.isEnabled = true }
                false }
            if (!itemView.resources.isLTR) moreBgGradient!!.rotation = 180f
            lessButton.setOnClickListener { collapseDesc(true) }

            // Tadami-style action buttons (FrameLayout with icon+label children)
            webviewButton.setOnClickListener { adapter.delegate.openInWebView() }
            shareButton.setOnClickListener { adapter.delegate.prepareToShareManga() }
            favoriteButton.setOnClickListener { adapter.delegate.favoriteManga(false) }
            favoriteButton.setOnLongClickListener { adapter.delegate.favoriteManga(true); true }
            updateIntervalButton!!.setOnClickListener { adapter.delegate.showTrackingSheet() }
            title.setOnClickListener { v ->
                title.text?.toString()?.toNormalized()?.let {
                    adapter.delegate.showFloatingActionMode(v as TextView, it) } }
            title.setOnLongClickListener {
                title.text?.toString()?.toNormalized()?.let {
                    adapter.delegate.copyContentToClipboard(it, MR.strings.title) }; true }
            mangaAuthor.setOnClickListener { v ->
                mangaAuthor.text?.toString()?.let {
                    adapter.delegate.showFloatingActionMode(v as TextView, it) } }
            mangaAuthor.setOnLongClickListener {
                mangaAuthor.text?.toString()?.let {
                    adapter.delegate.copyContentToClipboard(it, MR.strings.author) }; true }
            mangaArtist!!.setOnClickListener { v ->
                mangaArtist!!.text?.toString()?.let {
                    adapter.delegate.showFloatingActionMode(v as TextView, it) } }
            mangaArtist!!.setOnLongClickListener {
                mangaArtist!!.text?.toString()?.let {
                    adapter.delegate.copyContentToClipboard(it, MR.strings.artist) }; true }
            mangaSummary.customSelectionActionModeCallback = adapter.delegate.customActionMode(mangaSummary)
            applyBlur()
            mangaCover.setOnClickListener { adapter.delegate.zoomImageFromThumb(mangaCover) }
            trackButton.setOnClickListener { adapter.delegate.showTrackingSheet() }
            if (startExpanded) expandDesc() else collapseDesc()
            if (isTablet) { chapterLayout.isVisible = false; expandDesc() }
        }
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding?.backdrop?.alpha = 0.2f
            binding?.backdrop?.setRenderEffect(RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.MIRROR)) }
    }

    private fun expandDesc(animated: Boolean = false) {
        binding ?: return
        if (binding.moreButton.visibility == View.VISIBLE || isTablet) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            binding.mangaSummary.maxLines = Int.MAX_VALUE
            binding.mangaSummary.setTextIsSelectable(true)
            setDescription()
            binding.mangaGenresTags!!.isVisible = true
            binding.lessButton.isVisible = !isTablet
            binding.moreButtonGroup.isVisible = false
            if (animated) {
                val av = AnimatedVectorDrawableCompat.create(binding.root.context, R.drawable.anim_expand_more_to_less)
                binding.lessButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, av, null); av?.start() }
            binding.title.maxLines = Int.MAX_VALUE
            binding.mangaAuthor.maxLines = Int.MAX_VALUE
            binding.mangaSummary.requestFocus()
            if (animated) {
                val t = TransitionSet().addTransition(androidx.transition.ChangeBounds()).addTransition(androidx.transition.Fade()).addTransition(androidx.transition.Slide())
                t.duration = binding.root.resources.getInteger(AR.integer.config_shortAnimTime).toLong()
                androidx.transition.TransitionManager.beginDelayedTransition(adapter.controller.binding.recycler, t) }
        }
    }

    private fun collapseDesc(animated: Boolean = false) {
        binding ?: return; if (isTablet || !canCollapse) return
        binding.moreButtonGroup.isVisible = !isTablet
        if (animated) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            val av = AnimatedVectorDrawableCompat.create(binding.root.context, R.drawable.anim_expand_less_to_more)
            binding.moreButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, av, null); av?.start()
            val t = TransitionSet().addTransition(androidx.transition.ChangeBounds()).addTransition(androidx.transition.Fade())
            t.duration = binding.root.resources.getInteger(AR.integer.config_shortAnimTime).toLong()
            androidx.transition.TransitionManager.beginDelayedTransition(adapter.controller.binding.recycler, t) }
        binding.mangaSummary.setTextIsSelectable(false)
        binding.mangaSummary.isClickable = true; binding.mangaSummary.maxLines = 3
        setDescription(); binding.mangaGenresTags!!.isVisible = isTablet
        binding.lessButton.isVisible = false; binding.title.maxLines = 4; binding.mangaAuthor.maxLines = 2
        adapter.recyclerView.post { adapter.delegate.updateScroll() }
    }

    private fun setDescription() {
        if (binding == null) return
        val desc = adapter.controller.mangaPresenter().manga.description
        binding.mangaSummary.text = when {
            desc.isNullOrBlank() -> itemView.context.getString(MR.strings.no_description)
            binding.mangaSummary.maxLines != Int.MAX_VALUE -> desc.replace(Regex("[\\r\\n\\s*]{2,}", setOf(RegexOption.MULTILINE)), "\n")
            else -> desc.trim() }
    }

    fun bindChapters() {
        val presenter = adapter.delegate.mangaPresenter()
        populateScanlatorChips(presenter)
        val count = presenter.chapters.size
        if (binding != null) {
            binding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)
            binding.filtersText.text = presenter.currentFilters()
        } else if (chapterBinding != null) {
            chapterBinding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)
            chapterBinding.filtersText.text = presenter.currentFilters() }
    }

    @SuppressLint("SetTextI18n")
    fun bind(item: MangaHeaderItem) {
        val presenter = adapter.delegate.mangaPresenter()
        val manga = presenter.manga
        if (binding == null) {
            if (chapterBinding != null) {
                val count = presenter.chapters.size
                chapterBinding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)
                chapterBinding.filtersText.text = presenter.currentFilters()
                if (adapter.preferences.themeMangaDetails().get()) {
                    chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(adapter.delegate.accentColor() ?: return) } }
            return }

        binding.title.text = manga.title
        setGenreTags(binding, manga)

        // Author with icon
        binding.authorIcon!!.imageTintList = ColorStateList.valueOf(
            itemView.context.getResourceColor(R.attr.colorOnSurface))
        binding.mangaAuthor.text = manga.author?.trim() ?: ""

        // Artist row — visible only when artist differs from author
        val artist = if (manga.hasSameAuthorAndArtist) null else manga.artist?.trim()
        if (!artist.isNullOrBlank()) {
            binding.artistRow!!.isVisible = true
            binding.artistIcon!!.imageTintList = ColorStateList.valueOf(
                itemView.context.getResourceColor(R.attr.colorOnSurface))
            binding.mangaArtist!!.text = artist
        } else {
            binding.artistRow!!.isVisible = false
        }

        // Status with icon
        binding.mangaStatus!!.text = itemView.context.getString(when (manga.status) {
            SManga.ONGOING -> MR.strings.ongoing; SManga.COMPLETED -> MR.strings.completed
            SManga.LICENSED -> MR.strings.licensed; SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
            SManga.CANCELLED -> MR.strings.cancelled; SManga.ON_HIATUS -> MR.strings.on_hiatus
            else -> MR.strings.unknown_status })
        binding.mangaStatus!!.isVisible = manga.status != 0

        // Status icon matches Tadami's mapping
        binding.statusIcon!!.setImageResource(when (manga.status) {
            SManga.ONGOING -> R.drawable.ic_schedule_24dp
            SManga.COMPLETED -> R.drawable.ic_done_all_24dp
            SManga.LICENSED -> R.drawable.ic_attach_money_24dp
            SManga.PUBLISHING_FINISHED -> R.drawable.ic_done_24dp
            SManga.CANCELLED -> R.drawable.ic_close_24dp
            SManga.ON_HIATUS -> R.drawable.ic_pause_circle_outline_24dp
            else -> R.drawable.ic_block_24dp
        })

        setDescription()
        binding.mangaSummary.post {
            if (binding.subItemGroup.isVisible) {
                if (binding.mangaSummary.lineCount < 3 && manga.genre.isNullOrBlank() &&
                    binding.moreButton.isVisible && manga.initialized) {
                    expandDesc(); binding.lessButton.isVisible = false
                    showMoreButton = binding.lessButton.isVisible; canCollapse = false } }
            if (adapter.hasFilter()) { collapse() } else { expand() } }

        binding.mangaSummaryLabel.text = itemView.context.getString(MR.strings.about_this_, manga.seriesType(itemView.context))

        // Favorite button (FrameLayout with icon+label)
        val isFavorite = manga.favorite
        val favIcon = when {
            item.isLocked -> R.drawable.ic_lock_24dp
            isFavorite -> R.drawable.ic_heart_24dp
            else -> R.drawable.ic_heart_outline_24dp
        }
        val favText = itemView.context.getString(when {
            item.isLocked -> MR.strings.unlock
            isFavorite -> MR.strings.in_library
            else -> MR.strings.add_to_library
        })
        binding.favoriteButtonIcon!!.setImageResource(favIcon)
        binding.favoriteButtonLabel!!.text = favText
        if (isFavorite && !item.isLocked) {
            val accent = adapter.delegate.accentColor() ?: itemView.context.getResourceColor(R.attr.colorSecondary)
            binding.favoriteButtonIcon!!.imageTintList = ColorStateList.valueOf(accent)
            binding.favoriteButtonLabel!!.setTextColor(accent)
        } else {
            val muted = itemView.context.getResourceColor(R.attr.colorOnSurface)
            binding.favoriteButtonIcon!!.imageTintList = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(muted, 97))
            binding.favoriteButtonLabel!!.setTextColor(
                ColorUtils.setAlphaComponent(muted, 97))
        }

        binding.trueBackdrop.setBackgroundColor(
            adapter.delegate.coverColor() ?: itemView.context.getResourceColor(R.attr.background))

        // Tracking button (FrameLayout with icon+label)
        val tracked = presenter.isTracked() && !item.isLocked
        val hasTrackers = presenter.hasTrackers()
        binding.trackButton.isVisible = hasTrackers
        binding.trackButtonIcon!!.setImageResource(
            if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp)
        binding.trackButtonLabel!!.text = itemView.context.getString(
            if (tracked) MR.strings.tracked else MR.strings.tracking)
        if (tracked) {
            val accent = adapter.delegate.accentColor() ?: itemView.context.getResourceColor(R.attr.colorSecondary)
            binding.trackButtonIcon!!.imageTintList = ColorStateList.valueOf(accent)
            binding.trackButtonLabel!!.setTextColor(accent)
        } else {
            val muted = itemView.context.getResourceColor(R.attr.colorOnSurface)
            binding.trackButtonIcon!!.imageTintList = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(muted, 97))
            binding.trackButtonLabel!!.setTextColor(
                ColorUtils.setAlphaComponent(muted, 97))
        }

        // Update interval button
        binding.updateIntervalLabel!!.text = "N/A"

        // WebView button
        binding.webviewButton.isVisible = !manga.isLocal()
        binding.webviewButtonIcon!!.setImageResource(R.drawable.ic_public_24dp)
        binding.webviewButtonLabel!!.text = itemView.context.getString(MR.strings.open_in_webview)
        val webviewMuted = itemView.context.getResourceColor(R.attr.colorOnSurface)
        binding.webviewButtonIcon!!.imageTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(webviewMuted, 97))
        binding.webviewButtonLabel!!.setTextColor(
            ColorUtils.setAlphaComponent(webviewMuted, 97))

        // Share button visibility
        binding.shareButton.isVisible = !manga.isLocal()

        // Start reading button
        with(binding.startReadingButton) {
            val nextChapter = presenter.getNextUnreadChapter()
            isVisible = presenter.chapters.isNotEmpty() && !item.isLocked && !adapter.hasFilter()
            showReadingButton = isVisible; isEnabled = (nextChapter != null)
            text = if (nextChapter != null) {
                val number = adapter.decimalFormat.format(nextChapter.chapter_number.toDouble())
                if (nextChapter.chapter_number > 0) context.getString(
                    if (nextChapter.last_page_read > 0) MR.strings.continue_reading_chapter_ else MR.strings.start_reading_chapter_, number)
                else context.getString(
                    if (nextChapter.last_page_read > 0) MR.strings.continue_reading else MR.strings.start_reading)
            } else context.getString(MR.strings.all_chapters_read) }

        // Source
        with(binding.mangaSource) {
            val enabledLanguages = presenter.preferences.enabledLanguages().get()
            text = buildSpannedString {
                append(presenter.source.nameBasedOnEnabledLanguages(enabledLanguages))
                if (presenter.source is SourceManager.StubSource && presenter.source.name != presenter.source.id.toString())
                    scale(0.9f) { append(" (${context.getString(MR.strings.source_not_installed)})") } } }

        // Chapters
        val count = presenter.chapters.size
        binding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)
        binding.filtersText.text = presenter.currentFilters()
        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> { height = adapter.delegate.topCoverHeight() }
        // Scanlator branch selector
        populateScanlatorChips(presenter)


        if (!manga.initialized) return
        updateCover(manga)
        if (adapter.preferences.themeMangaDetails().get()) updateColors(false)
    }

    private fun setGenreTags(binding: MangaHeaderItemBinding, manga: Manga) {
        with(binding.mangaGenresTags) {
            removeAllViews()
            val dark = context.isInNightMode()
            val amoled = adapter.delegate.mangaPresenter().preferences.themeDarkAmoled().get()
            val baseTagColor = context.getResourceColor(R.attr.background)
            val bgArray = FloatArray(3); val accentArray = FloatArray(3)
            ColorUtils.colorToHSL(baseTagColor, bgArray)
            ColorUtils.colorToHSL(adapter.delegate.accentColor() ?: context.getResourceColor(R.attr.colorSecondary), accentArray)
            val downloadedColor = ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(floatArrayOf(
                if (adapter.delegate.accentColor() != null) accentArray[0] else bgArray[0], bgArray[1],
                when { amoled && dark -> 0.1f; dark -> 0.225f; else -> 0.85f })), 199)
            val textColor = ColorUtils.HSLToColor(floatArrayOf(accentArray[0], accentArray[1], if (dark) 0.945f else 0.175f))
            val states = arrayOf(intArrayOf(-AR.attr.state_activated), intArrayOf())
            val colors = intArrayOf(downloadedColor, ColorUtils.blendARGB(downloadedColor, context.getResourceColor(R.attr.colorControlNormal), 0.25f))
            val colorStateList = ColorStateList(states, colors)
            if (manga.genre.isNullOrBlank().not()) {
                (manga.getGenres() ?: emptyList()).map { genreText ->
                    val chip = LayoutInflater.from(binding.root.context).inflate(R.layout.genre_chip, this, false) as Chip
                    chip.id = View.generateViewId(); chip.chipBackgroundColor = colorStateList
                    chip.setTextColor(textColor); chip.text = genreText
                    chip.setOnClickListener { adapter.delegate.showFloatingActionMode(it as TextView, isTag = true) }
                    chip.setOnLongClickListener { adapter.delegate.copyContentToClipboard(genreText, genreText); true }
                    addView(chip) } } }
    }

    fun clearDescFocus() { binding?.mangaSummary?.let { it.setTextIsSelectable(false); it.clearFocus() } }

    private fun setCheckedState(
        icon: android.widget.ImageView,
        label: TextView,
        checked: Boolean,
    ) {
        val accentColor = adapter.delegate.accentColor()
            ?: itemView.context.getResourceColor(R.attr.colorSecondary)
        if (checked) {
            icon.imageTintList = ColorStateList.valueOf(accentColor)
            label.setTextColor(accentColor)
        } else {
            val muted = itemView.context.getResourceColor(R.attr.colorOnSurface)
            icon.imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(muted, 97))
            label.setTextColor(ColorUtils.setAlphaComponent(muted, 97))
        }
    }

    fun setTopHeight(newHeight: Int) { binding?.topView?.updateLayoutParams<ConstraintLayout.LayoutParams> { height = newHeight } }
    fun setBackDrop(color: Int) { binding?.trueBackdrop?.setBackgroundColor(color) }

    fun updateColors(updateAll: Boolean = true) {
        val accentColor = adapter.delegate.accentColor() ?: return
        if (binding == null) { chapterBinding?.filterButton?.imageTintList = ColorStateList.valueOf(accentColor); return }
        val manga = adapter.presenter.manga
        with(binding!!) {
            trueBackdrop.setBackgroundColor(adapter.delegate.coverColor() ?: trueBackdrop.context.getResourceColor(R.attr.background))
            TextViewCompat.setCompoundDrawableTintList(moreButton, ColorStateList.valueOf(accentColor))
            moreButton.setTextColor(accentColor)
            TextViewCompat.setCompoundDrawableTintList(lessButton, ColorStateList.valueOf(accentColor)); lessButton.setTextColor(accentColor)

            // Action button tinting
            shareButton.imageTintList = ColorStateList.valueOf(accentColor)
            filterButton.imageTintList = ColorStateList.valueOf(accentColor)

            // Author/Artist/Status icons
            val surfaceColor = root.context.getResourceColor(R.attr.colorOnSurface)
            authorIcon!!.imageTintList = ColorStateList.valueOf(surfaceColor)
            artistIcon!!.imageTintList = ColorStateList.valueOf(surfaceColor)
            statusIcon!!.imageTintList = ColorStateList.valueOf(surfaceColor)

            // WebView icon stays muted
            webviewButtonIcon!!.imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(surfaceColor, 97))
            webviewButtonLabel!!.setTextColor(ColorUtils.setAlphaComponent(surfaceColor, 97))

            // Start reading button colors
            val s = arrayOf(intArrayOf(-AR.attr.state_enabled), intArrayOf())
            val c = intArrayOf(ColorUtils.setAlphaComponent(root.context.getResourceColor(R.attr.tabBarIconInactive), 43), accentColor)
            startReadingButton.backgroundTintList = ColorStateList(s, c)
            val tc = intArrayOf(ColorUtils.setAlphaComponent(root.context.getResourceColor(R.attr.colorOnSurface), 97),
                root.context.getResourceColor(AR.attr.textColorPrimaryInverse))
            startReadingButton.setTextColor(ColorStateList(s, tc))

            if (updateAll) {
                val presenter = adapter.delegate.mangaPresenter()
                val tracked = presenter.isTracked() && !manga.favorite
                setCheckedState(trackButtonIcon!!, trackButtonLabel!!, tracked)
                setCheckedState(favoriteButtonIcon!!, favoriteButtonLabel!!, manga.favorite)
                setGenreTags(this, manga) } } }

    fun updateTracking() {
        binding ?: return; val presenter = adapter.delegate.mangaPresenter(); val tracked = presenter.isTracked()
        binding.trackButtonLabel!!.text = itemView.context.getString(
            if (tracked) MR.strings.tracked else MR.strings.tracking)
        binding.trackButtonIcon!!.setImageResource(
            if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp)
        setCheckedState(binding.trackButtonIcon!!, binding.trackButtonLabel!!, tracked)
    }

    fun collapse() {
        binding ?: return; if (!canCollapse) return
        binding.subItemGroup.isVisible = false; binding.startReadingButton.isVisible = false
        if (binding.moreButton.isVisible || binding.moreButton.isInvisible) binding.moreButtonGroup.isInvisible = !isTablet
        else { binding.lessButton.isVisible = false; binding.mangaGenresTags!!.isVisible = isTablet } }

    fun updateCover(manga: Manga) {
        binding ?: return; if (!manga.initialized) return
        val drawable = adapter.controller.binding.mangaCoverFull.drawable
        binding.mangaCover.loadManga(manga) {
            placeholder(drawable); error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY); diskCachePolicy(CachePolicy.READ_ONLY) }
        binding.backdrop.loadManga(manga) {
            placeholder(drawable); error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY); diskCachePolicy(CachePolicy.READ_ONLY)
            target(onSuccess = {
                val result = it.asDrawable(itemView.resources)
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap == null) { binding?.backdrop?.setImageDrawable(result); return@target }
                val yOffset = (bitmap.height / 2 * 0.33).toInt()
                binding?.backdrop?.setImageDrawable(
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height - yOffset).toDrawable(itemView.resources))
                applyBlur() }) } }

    fun expand() {
        binding ?: return; binding.subItemGroup.isVisible = true
        if (!showMoreButton) binding.moreButtonGroup.isVisible = false
        else if (binding.mangaSummary.maxLines != Int.MAX_VALUE) binding.moreButtonGroup.isVisible = !isTablet
        else { binding.lessButton.isVisible = !isTablet; binding.mangaGenresTags!!.isVisible = true }
        binding.startReadingButton.isVisible = showReadingButton }

    override fun onLongClick(view: View?): Boolean { super.onLongClick(view); return false }

    private fun populateScanlatorChips(presenter: MangaDetailsPresenter) {
        binding ?: return
        if (!presenter.showScanlatorBranches) {
            binding.scanlatorBranchGroup!!.isVisible = false
            return
        }
        val counts = presenter.scanlatorChapterCounts
        if (counts.isEmpty()) {
            binding.scanlatorBranchGroup!!.isVisible = false
            return
        }
        binding.scanlatorBranchGroup!!.isVisible = true
        binding.scanlatorBranchGroup!!.removeAllViews()
        val selected = presenter.selectedScanlator
        val accentColor = adapter.delegate.accentColor() ?: itemView.context.getResourceColor(R.attr.colorSecondary)
        val surfaceColor = itemView.context.getResourceColor(R.attr.colorOnSurface)
        
        // "All" chip
        addScanlatorChip(
            text = itemView.context.getString(MR.strings.all_scanlators),
            count = counts.values.sum(),
            isSelected = selected == null,
            accentColor = accentColor,
            surfaceColor = surfaceColor,
            onClick = { presenter.selectScanlator(null) }
        )
        
        counts.entries.sortedByDescending { it.value }.forEach { (scanlator, count) ->
            addScanlatorChip(
                text = scanlator,
                count = count,
                isSelected = selected == scanlator,
                accentColor = accentColor,
                surfaceColor = surfaceColor,
                onClick = { presenter.selectScanlator(scanlator) }
            )
        }
    }
    
    private fun addScanlatorChip(
        text: String,
        count: Int,
        isSelected: Boolean,
        accentColor: Int,
        surfaceColor: Int,
        onClick: () -> Unit,
    ) {
        binding ?: return
        val chip = Chip(itemView.context)
        chip.text = itemView.context.getString(MR.strings.scanlator_chapter_count, text, count)
        chip.setOnClickListener { onClick() }
        val textColor = if (isSelected) accentColor else ColorUtils.setAlphaComponent(surfaceColor, 180)
        chip.setTextColor(textColor)
        chip.chipBackgroundColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(if (isSelected) accentColor else surfaceColor, 30)
        )
        chip.chipStrokeColor = ColorStateList.valueOf(
            if (isSelected) accentColor else ColorUtils.setAlphaComponent(surfaceColor, 60)
        )
        chip.chipStrokeWidth = 1f
        binding.scanlatorBranchGroup!!.addView(chip)
    }
}