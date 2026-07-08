package eu.kanade.tachiyomi.ui.manga

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.databinding.ChapterHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaDetailsBottomSheetBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.nameBasedOnEnabledLanguages
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
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

    var hadSelection = false
    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    internal var bottomSheetBinding: MangaDetailsBottomSheetBinding? = null

    init {
        if (binding == null) {
            chapterBinding?.chapterLayout?.setOnClickListener {
                adapter.delegate.showChapterFilter() }
        } else with(binding) {
            topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = adapter.delegate.topCoverHeight() }
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
            mangaCover.setOnClickListener { adapter.delegate.zoomImageFromThumb(mangaCover) }
            // Tap hint to expand bottom sheet
            detailsExpandHint?.setOnClickListener {
                bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED }
            setupBottomSheet()
        }
    }

    private fun setupBottomSheet() {
        val root = itemView.rootView.findViewById<View>(R.id.details_bottom_sheet) ?: return
        bottomSheetBehavior = BottomSheetBehavior.from(root)
        bottomSheetBinding = MangaDetailsBottomSheetBinding.bind(
            root.findViewById(R.id.bottom_sheet))
        bottomSheetBinding?.let { bs ->
            bs.bsTrackButton.setOnClickListener { adapter.delegate.showTrackingSheet() }
            bs.bsWebviewButton.setOnClickListener { adapter.delegate.openInWebView() }
            bs.bsShareButton.setOnClickListener { adapter.delegate.prepareToShareManga() }
            bs.bsChapterHeader.setOnClickListener { adapter.delegate.showChapterFilter() }
        }
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding?.backdrop?.alpha = 0.2f
            binding?.backdrop?.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.MIRROR))
        }
    }

    fun bindChapters() {
        val presenter = adapter.delegate.mangaPresenter()
        val count = presenter.chapters.size
        bottomSheetBinding?.bsChaptersTitle?.text =
            itemView.context.getString(MR.plurals.chapters_plural, count, count)
        bottomSheetBinding?.bsFiltersText?.text = presenter.currentFilters()
    }

    @SuppressLint("SetTextI18n")
    fun bind(item: MangaHeaderItem) {
        val presenter = adapter.delegate.mangaPresenter()
        val manga = presenter.manga
        if (binding == null) {
            if (chapterBinding != null) {
                val count = presenter.chapters.size
                chapterBinding.chaptersTitle.text =
                    itemView.context.getString(MR.plurals.chapters_plural, count, count)
                chapterBinding.filtersText.text = presenter.currentFilters()
                if (adapter.preferences.themeMangaDetails().get()) {
                    chapterBinding.filterButton.imageTintList =
                        ColorStateList.valueOf(adapter.delegate.accentColor() ?: return) }
            }
            return
        }
        binding.title.text = manga.title
        binding.mangaAuthor.text = if (manga.hasSameAuthorAndArtist) manga.author?.trim()
            else listOfNotNull(manga.author?.trim(), manga.artist?.trim()).joinToString(", ")

        binding.trueBackdrop.setBackgroundColor(
            adapter.delegate.coverColor() ?: itemView.context.getResourceColor(R.attr.background))
        binding.mangaStatus.isVisible = manga.status != 0
        binding.mangaStatus.text = itemView.context.getString(when (manga.status) {
            SManga.ONGOING -> MR.strings.ongoing; SManga.COMPLETED -> MR.strings.completed
            SManga.LICENSED -> MR.strings.licensed; SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
            SManga.CANCELLED -> MR.strings.cancelled; SManga.ON_HIATUS -> MR.strings.on_hiatus
            else -> MR.strings.unknown_status })
        with(binding.mangaSource) {
            val enabledLanguages = presenter.preferences.enabledLanguages().get()
            text = buildSpannedString {
                append(presenter.source.nameBasedOnEnabledLanguages(enabledLanguages))
                if (presenter.source is SourceManager.StubSource &&
                    presenter.source.name != presenter.source.id.toString())
                    scale(0.9f) { append(" (${context.getString(MR.strings.source_not_installed)})") } }
        }
        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> { height = adapter.delegate.topCoverHeight() }

        populateBottomSheet(manga)
        bindBottomBar(item, manga)

        val count = presenter.chapters.size
        bottomSheetBinding?.bsChaptersTitle?.text =
            itemView.context.getString(MR.plurals.chapters_plural, count, count)
        bottomSheetBinding?.bsFiltersText?.text = presenter.currentFilters()

        if (!manga.initialized) return
        updateCover(manga)
        if (adapter.preferences.themeMangaDetails().get()) updateColors(false)
    }

    private fun bindBottomBar(item: MangaHeaderItem, manga: Manga) {
        val bar = itemView.rootView.findViewById<LinearLayout>(R.id.bottom_bar) ?: return
        val favBtn = bar.findViewById<MaterialButton>(R.id.bb_favorite_button) ?: return
        val startBtn = bar.findViewById<MaterialButton>(R.id.bb_start_reading_button) ?: return
        val presenter = adapter.delegate.mangaPresenter()

        favBtn.icon = ContextCompat.getDrawable(itemView.context, when {
            item.isLocked -> R.drawable.ic_lock_24dp; manga.favorite -> R.drawable.ic_heart_24dp
            else -> R.drawable.ic_heart_outline_24dp })
        favBtn.text = itemView.context.getString(when {
            item.isLocked -> MR.strings.unlock; manga.favorite -> MR.strings.in_library
            else -> MR.strings.add_to_library })
        checkedState(favBtn, !item.isLocked && manga.favorite)
        favBtn.setOnClickListener { adapter.delegate.favoriteManga(false) }
        favBtn.setOnLongClickListener { adapter.delegate.favoriteManga(true); true }
        adapter.delegate.setFavButtonPopup(favBtn)

        val nextChapter = presenter.getNextUnreadChapter()
        startBtn.isVisible = presenter.chapters.isNotEmpty() && !item.isLocked && !adapter.hasFilter()
        startBtn.isEnabled = (nextChapter != null)
        startBtn.transitionName = "details start reading transition"
        startBtn.text = if (nextChapter != null) {
            val number = adapter.decimalFormat.format(nextChapter.chapter_number.toDouble())
            itemView.context.getString(
                if (nextChapter.chapter_number > 0) {
                    if (nextChapter.last_page_read > 0) MR.strings.continue_reading_chapter_
                    else MR.strings.start_reading_chapter_ }
                else { if (nextChapter.last_page_read > 0) MR.strings.continue_reading
                    else MR.strings.start_reading }, number)
        } else itemView.context.getString(MR.strings.all_chapters_read)
        startBtn.setOnClickListener { adapter.delegate.readNextChapter(it) }
    }

    private fun populateBottomSheet(manga: Manga) {
        val bs = bottomSheetBinding ?: return
        val presenter = adapter.delegate.mangaPresenter()
        bs.bsSummary.text = if (manga.description.isNullOrBlank())
            itemView.context.getString(MR.strings.no_description) else manga.description?.trim()
        bs.bsSummaryLabel.text = itemView.context.getString(
            MR.strings.about_this_, manga.seriesType(itemView.context))
        with(bs.bsGenresTags) {
            removeAllViews()
            val dark = itemView.context.isInNightMode()
            val baseTagColor = itemView.context.getResourceColor(R.attr.background)
            val bgArray = FloatArray(3); val accentArray = FloatArray(3)
            ColorUtils.colorToHSL(baseTagColor, bgArray)
            ColorUtils.colorToHSL(adapter.delegate.accentColor()
                ?: itemView.context.getResourceColor(R.attr.colorSecondary), accentArray)
            val amoled = presenter.preferences.themeDarkAmoled().get()
            val bg = ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(floatArrayOf(
                if (adapter.delegate.accentColor() != null) accentArray[0] else bgArray[0],
                bgArray[1], when { amoled && dark -> 0.1f; dark -> 0.225f; else -> 0.85f })), 199)
            val tc = ColorUtils.HSLToColor(floatArrayOf(accentArray[0], accentArray[1],
                if (dark) 0.945f else 0.175f))
            val cl = ColorStateList(arrayOf(intArrayOf(-AR.attr.state_activated), intArrayOf()),
                intArrayOf(bg, ColorUtils.blendARGB(bg,
                    itemView.context.getResourceColor(R.attr.colorControlNormal), 0.25f)))
            if (!manga.genre.isNullOrBlank()) (manga.getGenres() ?: emptyList()).forEach { g ->
                val chip = LayoutInflater.from(itemView.context).inflate(
                    R.layout.genre_chip, this, false) as Chip
                chip.id = View.generateViewId(); chip.chipBackgroundColor = cl
                chip.setTextColor(tc); chip.text = g
                chip.setOnClickListener { adapter.delegate.showFloatingActionMode(it, isTag = true) }
                chip.setOnLongClickListener { adapter.delegate.copyContentToClipboard(g, g); true }
                addView(chip) }
        }
        val tracked = presenter.isTracked() && !(presenter.headerItem.isLocked)
        bs.bsTrackButton.apply {
            isVisible = presenter.hasTrackers()
            text = itemView.context.getString(if (tracked) MR.strings.tracked else MR.strings.tracking)
            icon = ContextCompat.getDrawable(itemView.context,
                if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp)
            checkedState(this, tracked) }
    }

    private fun checkedState(btn: MaterialButton, checked: Boolean) {
        if (checked) {
            val acc = adapter.delegate.accentColor() ?: btn.context.getResourceColor(R.attr.colorSecondary)
            btn.backgroundTintList = ColorStateList.valueOf(
                ColorUtils.blendARGB(acc, btn.context.getResourceColor(R.attr.background), 0.706f))
            btn.strokeColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        } else { btn.resetStrokeColor()
            btn.backgroundTintList = ColorStateList.valueOf(btn.context.getResourceColor(R.attr.background)) }
    }

    fun setTopHeight(newHeight: Int) { binding?.topView?.updateLayoutParams<ConstraintLayout.LayoutParams> { height = newHeight } }
    fun setBackDrop(color: Int) { binding?.trueBackdrop?.setBackgroundColor(color) }

    fun updateColors(updateAll: Boolean = true) {
        val accent = adapter.delegate.accentColor() ?: return
        if (binding == null) { chapterBinding?.filterButton?.imageTintList = ColorStateList.valueOf(accent); return }
        binding.trueBackdrop.setBackgroundColor(
            adapter.delegate.coverColor() ?: binding.root.context.getResourceColor(R.attr.background))
        bottomSheetBinding?.let { bs ->
            bs.bsFilterButton.imageTintList = ColorStateList.valueOf(accent)
            bs.bsTrackButton.iconTint = ColorStateList.valueOf(accent)
            bs.bsWebviewButton.imageTintList = ColorStateList.valueOf(accent)
            bs.bsShareButton.imageTintList = ColorStateList.valueOf(accent) }
        val bar = itemView.rootView.findViewById<LinearLayout>(R.id.bottom_bar)
        bar?.findViewById<MaterialButton>(R.id.bb_favorite_button)?.iconTint = ColorStateList.valueOf(accent)
        if (updateAll) populateBottomSheet(adapter.presenter.manga)
    }

    fun updateTracking() {
        val presenter = adapter.delegate.mangaPresenter(); val tracked = presenter.isTracked()
        bottomSheetBinding?.bsTrackButton?.apply {
            text = itemView.context.getString(if (tracked) MR.strings.tracked else MR.strings.tracking)
            icon = ContextCompat.getDrawable(itemView.context,
                if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp) }
    }

    fun updateCover(manga: Manga) {
        binding ?: return; if (!manga.initialized) return
        val drawable = adapter.controller.binding.mangaCoverFull.drawable
        binding.mangaCover.loadManga(manga) {
            placeholder(drawable); error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY)
            diskCachePolicy(CachePolicy.READ_ONLY) }
        binding.backdrop.loadManga(manga) {
            placeholder(drawable); error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY)
            diskCachePolicy(CachePolicy.READ_ONLY)
            target(onSuccess = {
                val result = it.asDrawable(itemView.resources)
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap == null) { binding?.backdrop?.setImageDrawable(result); return@target }
                val yOffset = (bitmap.height / 2 * 0.33).toInt()
                binding?.backdrop?.setImageDrawable(
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height - yOffset)
                        .toDrawable(itemView.resources))
                applyBlur() }) }
    }

    override fun onLongClick(view: View?): Boolean { super.onLongClick(view); return false }
}