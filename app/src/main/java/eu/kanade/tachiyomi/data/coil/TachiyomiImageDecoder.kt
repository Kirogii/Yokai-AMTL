package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.util.image.ImageFilter
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import okio.BufferedSource
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 * It also handles on-the-fly image enhancement via Waifu2x models and ink filter.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult? {
        return resources.source().use { source ->
            decodeSemaphore.withPermit {
                try {
                    var bitmap: Bitmap? = null
                    var sampleSize = 1

                    // 1. Attempt decoding with native ImageDecoder (for AVIF/JXL/HEIF)
                    val nativeDecoder = try {
                        ImageDecoder.newInstance(source.inputStream(), options.cropBorders, displayProfile)
                    } catch (e: Exception) {
                        null
                    }

                    if (nativeDecoder != null && nativeDecoder.width > 0 && nativeDecoder.height > 0) {
                        try {
                            val srcWidth = nativeDecoder.width
                            val srcHeight = nativeDecoder.height
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            sampleSize = DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = dstWidth,
                                dstHeight = dstHeight,
                                scale = options.scale,
                            )
                            bitmap = nativeDecoder.decode(sampleSize = sampleSize)
                        } finally {
                            nativeDecoder.recycle()
                        }
                    }

                    // 2. Fallback to BitmapFactory for system-supported formats (JPG, PNG, WEBP, etc.)
                    if (bitmap == null) {
                        try {
                            val byteBuf = source.peek().readByteArray()
                            val ops = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, ops)

                            if (ops.outWidth > 0 && ops.outHeight > 0) {
                                val srcWidth = ops.outWidth
                                val srcHeight = ops.outHeight
                                val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                                val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                                sampleSize = DecodeUtils.calculateInSampleSize(
                                    srcWidth = srcWidth,
                                    srcHeight = srcHeight,
                                    dstWidth = dstWidth,
                                    dstHeight = dstHeight,
                                    scale = options.scale,
                                )

                                val decodeOps = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                    inPreferredConfig = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
                                        Bitmap.Config.ARGB_8888 // Decode to software first
                                    } else {
                                        options.bitmapConfig
                                    }
                                }
                                bitmap = BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, decodeOps)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TachiyomiImageDecoder", "BitmapFactory fallback failed", e)
                        }
                    }

                    if (bitmap == null) {
                        android.util.Log.e("TachiyomiImageDecoder", "Failed to decode bitmap via all methods")
                        return@withPermit null
                    }

                    // --- Enhancement Integration ---
                    if (options.enhanced) {
                        val readerPrefs = Injekt.get<ReaderPreferences>()
                        if (readerPrefs.realCuganEnabled().get()) {
                            val mangaId = options.mangaId
                            val chapterId = options.chapterId
                            val pageIndex = options.pageIndex
                            val pageVariant = options.pageVariant

                            android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant enhanced=true, manga=$mangaId, chapter=$chapterId")

                            if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                                val context = Injekt.get<android.app.Application>()
                                ImageEnhancementCache.init(context)

                                val configHash = ImageEnhancementCache.getConfigHash(
                                    readerPrefs.realCuganNoiseLevel().get(),
                                    readerPrefs.realCuganScale().get(),
                                    readerPrefs.realCuganInputScale().get(),
                                    readerPrefs.realCuganModel().get(),
                                    readerPrefs.realCuganMaxSizeWidth().get(),
                                    readerPrefs.realCuganMaxSizeHeight().get(),
                                    true
                                )
                                android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant configHash=$configHash")

                                // Check cache first
                                var usedCache = false
                                val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                if (cachedFile != null) {
                                    android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant found in cache: ${cachedFile.absolutePath}")
                                    try {
                                        val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                        if (cachedBitmap != null) {
                                            bitmap.recycle()
                                            bitmap = cachedBitmap
                                            usedCache = true
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("TachiyomiImageDecoder", "Failed to decode cached enhanced image", e)
                                    }
                                }

                                if (!usedCache) {
                                    android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant NOT in cache or decode failed, processing...")
                                    // Not in cache or decode failed, perform enhancement on-the-fly
                                    try {
                                        val model = readerPrefs.realCuganModel().get()
                                        val noise = readerPrefs.realCuganNoiseLevel().get()
                                        var scale = readerPrefs.realCuganScale().get()

                                        // --- Target Resolution Check / Prescale ---
                                        val maxWidth = readerPrefs.realCuganMaxSizeWidth().get()
                                        val maxHeight = readerPrefs.realCuganMaxSizeHeight().get()
                                        val shouldResize = true
                                        var shouldSkipEnhancement = false

                                        val targetWidth = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
                                        val targetHeight = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
                                        val hasTargetResolution = maxWidth > 0 || maxHeight > 0
                                        val exceedsLimit = hasTargetResolution &&
                                            (bitmap.width > targetWidth || bitmap.height > targetHeight)

                                        if (exceedsLimit) {
                                            android.util.Log.d("TachiyomiImageDecoder",
                                                "Skipping enhancement for page $pageIndex - source ${bitmap.width}x${bitmap.height} exceeds target ${maxWidth}x${maxHeight}")
                                            ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                            shouldSkipEnhancement = true
                                        }

                                        // --- Performance Mode ---
                                        val perfMode = readerPrefs.realCuganPerformanceMode().get()
                                        val tileSleepMs = when (perfMode) {
                                            1, 2 -> 15
                                            else -> 0
                                        }
                                        val tileSize = when (perfMode) {
                                            1 -> 96
                                            2 -> 64
                                            else -> 128
                                        }

                                        // Validate scale based on model capabilities
                                        val effectiveScale = when (model) {
                                            3 -> 2 // Nose: fixed 2x
                                            5 -> 2 // Waifu2x Upconv7: only supports 2x
                                            else -> scale
                                        }
                                        if (effectiveScale != scale) {
                                            android.util.Log.d("TachiyomiImageDecoder", "Model $model only supports ${effectiveScale}x, clamping from ${scale}x")
                                        }

                                        if (!shouldSkipEnhancement && shouldResize && hasTargetResolution) {
                                            val finalWidthAtScale = bitmap.width * effectiveScale.toFloat()
                                            val finalHeightAtScale = bitmap.height * effectiveScale.toFloat()
                                            val ratio = min(
                                                targetWidth / finalWidthAtScale,
                                                targetHeight / finalHeightAtScale,
                                            )

                                            if (ratio in 0f..<1f) {
                                                val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                                                val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                                                android.util.Log.d("TachiyomiImageDecoder",
                                                    "Prescaling page $pageIndex with native scaling ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}, target=${maxWidth}x${maxHeight}, scale=${effectiveScale}x")
                                                val scaledBitmap = nativeScaleBitmap(bitmap, newWidth, newHeight)
                                                if (scaledBitmap != bitmap) {
                                                    bitmap.recycle()
                                                    bitmap = scaledBitmap
                                                }
                                            }
                                        }
                                        // --- End Target Resolution Check / Prescale ---

                                        if (!shouldSkipEnhancement) {
                                            val initialized = when (model) {
                                                0 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                1 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                2 -> Waifu2x.initRealESRGAN(context, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                3 -> Waifu2x.initNose(context, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                4 -> Waifu2x.initWaifu2x(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                5 -> Waifu2x.initWaifu2xUpconv7(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                                else -> Waifu2x.initRealCugan(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            }

                                            if (initialized) {
                                                val processed = when (model) {
                                                    0, 1 -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                                    2 -> Waifu2x.processRealESRGAN(bitmap, pageIndex)
                                                    3 -> Waifu2x.processNose(bitmap, pageIndex)
                                                    4, 5 -> Waifu2x.processWaifu2x(bitmap, pageIndex)
                                                    else -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                                }

                                                if (processed != null) {
                                                    val prefStore = Injekt.get<PreferenceStore>()
                                                    var result = ImageFilter.applyInkFilterIfEnabled(processed, prefStore)

                                                    // --- Output Resolution Limit (prevent Canvas errors) ---
                                                    val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                                                    android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex enhanced result: ${result.width}x${result.height}, DEVICE_TEXTURE_LIMIT=$textureLimit")

                                                    if (result.width > textureLimit || result.height > textureLimit) {
                                                        val widthRatio = textureLimit.toFloat() / result.width
                                                        val heightRatio = textureLimit.toFloat() / result.height
                                                        val ratio = Math.min(widthRatio, heightRatio)

                                                        val newWidth = (result.width * ratio).toInt().coerceAtLeast(1)
                                                        val newHeight = (result.height * ratio).toInt().coerceAtLeast(1)

                                                        android.util.Log.d("TachiyomiImageDecoder", "Output downscale page $pageIndex: ${result.width}x${result.height} -> ${newWidth}x${newHeight} (Texture Limit: $textureLimit)")
                                                        val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                                                        if (downscaled != result) {
                                                            result.recycle()
                                                            result = downscaled
                                                        }
                                                    }
                                                    // --- End Output Resolution Limit ---

                                                    val savedFile = ImageEnhancementCache.saveToCache(mangaId, chapterId, pageIndex, configHash, result, pageVariant)
                                                    if (savedFile != null) {
                                                        android.util.Log.d("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant saved to cache: ${savedFile.absolutePath}")
                                                    } else {
                                                        android.util.Log.e("TachiyomiImageDecoder", "Page $pageIndex/$pageVariant FAILED to save to cache")
                                                    }
                                                    if (bitmap != result) bitmap.recycle()
                                                    bitmap = result
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("TachiyomiImageDecoder", "Failed to enhance image on-the-fly", e)
                                    }
                                }
                            }
                        }
                    }
                    // --- End Enhancement Integration ---

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        options.bitmapConfig == Bitmap.Config.HARDWARE &&
                        !ImageUtil.isHardwareThresholdExceeded(bitmap)
                    ) {
                        val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                        if (hwBitmap != null) {
                            bitmap.recycle()
                            bitmap = hwBitmap
                        }
                    }

                    DecodeResult(
                        image = bitmap.asImage(),
                        isSampled = sampleSize > 1,
                    )
                } catch (e: Exception) {
                    android.util.Log.e("TachiyomiImageDecoder", "Critical failure during decode", e)
                    null
                }
            }
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source)) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: ImageSource): Boolean {
            val type = try {
                source.source().peek().inputStream().use { ImageUtil.findImageType(it) }
            } catch (e: Exception) {
                null
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory
        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
        private val decodeSemaphore = Semaphore(1)
    }
}

private fun nativeScaleBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    return Waifu2x.scaleBitmapNative(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
    ) ?: Bitmap.createScaledBitmap(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
        true,
    )
}
