package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Activity
import android.content.Context
import android.graphics.PointF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.ui.settings.ReaderPreferences
import yokai.domain.ui.settings.ReaderPreferences.CutoutBehaviour

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected var pageView: View? = null

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: (() -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    open fun onNeedsLandscapeZoom() { }

    // ===== Enhancement (waifu2x) integration =====

    /**
     * If true, this view updates the global current page index when [onPageSelected] is invoked.
     * Disabled for split-page dummy holders so they don't clobber the real current page.
     */
    var controlsCurrentPageSelection: Boolean = true

    /** Optional factory that converts an enhanced cached [java.io.File] into a [BufferedSource]. */
    var enhancedImageSourceFactory: ((java.io.File) -> BufferedSource?)? = null

    /** When true, the status overlay (if any) is suppressed by an outer holder. */
    var suppressDefaultStatus = false

    /** Optional override for the raw stream used to seed the enhancer. */
    var enhancementStreamOverride: (() -> java.io.InputStream)? = null

    /** Reveal-window fractions used by callers that animate the enhanced swap (kept for API parity). */
    var processedTransitionStartFraction: Float = 0f
    var processedTransitionEndFraction: Float = 1f

    /** Identity of the page this view is currently bound to. */
    var pageIndex: Int = -1
    var mangaId: Long = -1L
    var chapterId: Long = -1L

    /** The [ReaderPage] (if any) bound to this view — used as the source of truth for IDs/variant. */
    var readerPage: ReaderPage? = null

    /** Optional override for the enhancement variant key (e.g. split-page suffix). */
    var enhancementVariantOverride: String? = null

    private var enhancedBitmap: android.graphics.Bitmap? = null
    private var processingJob: Job? = null

    /** Path of the file currently displayed, when it came from the enhancement cache. */
    private var currentLoadedPath: String? = null

    /** True while we are intentionally loading the enhanced image (prevents re-poll loops). */
    private var isSettingProcessedImage = false

    private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        // Only poll for an enhanced version if we are not already displaying one.
        if (!isSettingProcessedImage) {
            startEnhancementPolling()
        }
    }

    @CallSuper
    open fun onImageLoadError() {
        onImageLoadError?.invoke()
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    /**
     * Called when this page becomes the active page. Reprioritizes enhancement work around
     * this page so the user-facing page is processed first.
     */
    open fun onPageSelected(forward: Boolean) {
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        if (controlsCurrentPageSelection && pIdx >= 0) {
            currentGlobalPageIndex = pIdx
        }

        if (mId != -1L && cId != -1L && pIdx >= 0) {
            ImageEnhancer.reprioritizeAround(pIdx, enhancementVariant())
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config

        // === Enhancement cache check (G) ===
        // Before loading the raw stream, check whether an enhanced version is already cached.
        // If so, substitute it for the raw source so we display the enhanced image immediately.
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        val effectiveSource: BufferedSource = if (
            !isAnimated &&
            readerPreferences.realCuganEnabled().get() &&
            mId != -1L && cId != -1L && pIdx >= 0
        ) {
            try {
                ImageEnhancementCache.init(context)
                val configHash = buildEnhancementConfigHash()
                val pageVariant = enhancementVariant()
                val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                if (cachedFile != null) {
                    currentLoadedPath = cachedFile.absolutePath
                    isSettingProcessedImage = true
                    enhancedImageSourceFactory?.invoke(cachedFile)
                        ?: Buffer().readFrom(cachedFile.inputStream())
                } else {
                    // Not cached yet; load the raw source and let polling swap in the enhanced image.
                    currentLoadedPath = null
                    isSettingProcessedImage = false
                    source
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "ReaderPageImageView: enhancement cache lookup failed: ${e.message}" }
                source
            }
        } else {
            source
        }

        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(effectiveSource, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(effectiveSource, config)
        }
    }

    fun recycle() = pageView?.let {
        processingJob?.cancel()
        processingJob = null
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        currentLoadedPath = null
        invalidate()
    }

    // ===== Enhancement helpers =====

    /** Variant key used when looking up enhanced cache entries (e.g. split-page "L"/"R"). */
    private fun enhancementVariant(): String {
        return enhancementVariantOverride ?: readerPage?.enhancementKeySuffix.orEmpty()
    }

    private fun buildEnhancementConfigHash(): String {
        return ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true,
        )
    }

    /**
     * Polls [ImageEnhancementCache] every ~300ms for an enhanced version of the currently-bound
     * page. When the enhanced file appears, swap it into the [SubsamplingScaleImageView].
     *
     * Safe to call from [onImageLoaded]: it short-circuits when the enhanced image is already
     * displayed, when enhancement is disabled, or when the page identity is unknown.
     */
    private fun startEnhancementPolling() {
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (mId == -1L || cId == -1L || pIdx < 0) return
        if (!readerPreferences.realCuganEnabled().get()) return

        ImageEnhancementCache.init(context)
        val configHash = buildEnhancementConfigHash()
        val pageVariant = enhancementVariant()

        // Already showing this exact enhanced file — nothing to do.
        val current = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
        if (current != null && current.absolutePath == currentLoadedPath) return

        // Skipped pages never get enhanced — stop polling.
        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) return

        processingJob?.cancel()
        processingJob = viewScope.launch(Dispatchers.Main.immediate) {
            try {
                while (isActive) {
                    if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                        break
                    }
                    val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                    if (file != null) {
                        if (file.absolutePath != currentLoadedPath) {
                            swapToEnhancedFile(file)
                        }
                        break
                    }
                    delay(300)
                }
            } catch (_: CancellationException) {
                // Detached / recycled — expected.
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "ReaderPageImageView: enhancement polling failed: ${e.message}" }
            }
        }
    }

    /**
     * Swaps the currently displayed image to the enhanced [file] by reusing the main project's
     * standard [prepareNonAnimatedImageView] + [setNonAnimatedImage] pipeline. This avoids the
     * fork's overlay/transitionDivider approach (which doesn't fit the main architecture).
     */
    private fun swapToEnhancedFile(file: java.io.File) {
        val cfg = config ?: return
        try {
            val stream = enhancedImageSourceFactory?.invoke(file)
                ?: Buffer().readFrom(file.inputStream())
            isSettingProcessedImage = true
            currentLoadedPath = file.absolutePath
            prepareNonAnimatedImageView()
            setNonAnimatedImage(stream, cfg)
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "ReaderPageImageView: failed to swap enhanced image: ${e.message}" }
            isSettingProcessedImage = false
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    protected fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        config ?: return

        var centerV = 0f
        when (config.zoomStartPosition) {
            PagerConfig.ZoomType.Left -> {
                setScaleAndCenter(scale, PointF(0f, 0f))
            }
            PagerConfig.ZoomType.Right -> {
                setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0f))
                centerV = sWidth.toFloat()
            }
            PagerConfig.ZoomType.Center -> {
                setScaleAndCenter(scale, center.also { it?.y = 0f })
                centerV = center?.x ?: 0f
            }
        }
        val insetInfo = config.insetInfo ?: return
        val topInsets = insetInfo.topCutoutInset
        val bottomInsets = insetInfo.bottomCutoutInset
        if (insetInfo.cutoutBehavior == CutoutBehaviour.SHOW &&
            topInsets + bottomInsets > 0 &&
            insetInfo.scaleTypeIsFullFit
        ) {
            setScaleAndCenter(
                scale,
                PointF(centerV, (center?.y?.plus(topInsets)?.minus(bottomInsets) ?: 0f)),
            )
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDebug(config.debugMode)
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        if (config.insetInfo != null) {
            val topInsets = config.insetInfo.topCutoutInset
            val bottomInsets = config.insetInfo.bottomCutoutInset
            setExtendPastCutout(
                config.insetInfo.cutoutBehavior == CutoutBehaviour.SHOW &&
                    config.insetInfo.scaleTypeIsFullFit && topInsets + bottomInsets > 0,
            )
            if ((config.insetInfo.cutoutBehavior != CutoutBehaviour.IGNORE || !config.insetInfo.scaleTypeIsFullFit) &&
                config.insetInfo.isFullscreen
            ) {
                val insets: WindowInsets? = config.insetInfo.insets
                setExtraSpace(
                    0f,
                    DeviceUtil.getCutoutHeight(context as? Activity, config.insetInfo.cutoutSupport).toFloat(),
                    0f,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                        insets?.displayCutout?.boundingRectBottom?.height()?.toFloat() ?: 0f
                    else 0f,
                )
            }
        }
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // 5x zoom
                    setupZoom(config)
                    this@ReaderPageImageView.onNeedsLandscapeZoom()
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError()
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                // SSIV doesn't tile bitmaps, so if the image exceeded max texture size it won't load regardless.
                if (!isWebtoon || ImageUtil.isMaxTextureSizeExceeded(data)) {
                    setHardwareConfig(!ImageUtil.isHardwareThresholdExceeded(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                        onError = {
                            onImageLoadError()
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
                onError = {
                    this@ReaderPageImageView.onImageLoadError()
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cancel any pending enhancement polling/requests for this page.
        processingJob?.cancel()
        processingJob = null

        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (mId != -1L && cId != -1L && pIdx >= 0) {
            ImageEnhancer.cancel(mId, cId, pIdx, enhancementVariant())
        }

        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        currentLoadedPath = null
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: PagerConfig.ZoomType = PagerConfig.ZoomType.Center,
        val landscapeZoom: Boolean = false,
        val insetInfo: InsetInfo? = null,
        val hingeGapSize: Int = 0,
        val debugMode: Boolean = false,
    )

    data class InsetInfo(
        val cutoutSupport: DeviceUtil.CutoutSupport,
        val cutoutBehavior: CutoutBehaviour,
        val topCutoutInset: Float,
        val bottomCutoutInset: Float,
        val scaleTypeIsFullFit: Boolean,
        val isFullscreen: Boolean,
        val isSplitScreen: Boolean,
        val insets: WindowInsets?,
    )

    companion object {
        /**
         * Global index of the page the user is currently viewing. Used by individual view instances
         * to decide whether to self-heal stale enhanced cache entries and to skip reprioritization
         * for non-active pages.
         */
        var currentGlobalPageIndex: Int = -1
    }
}

private const val MAX_ZOOM_SCALE = 5F