# Upscale Integration TODO

## Status
- [x] Assets (models, shaders) - already identical
- [x] C++ JNI native code - already identical
- [x] Kotlin upscale core (ImageEnhancer, Waifu2x, Cache) - already identical
- [x] ReaderPreferences - already identical
- [x] TachiyomiImageDecoder - already has realCugan check
- [x] SettingsReaderController - already has full upscale settings UI
- [x] third_party/ncnn SDK - already present

## Completed
1. [x] **build.gradle.kts** - Added cmake path block
5. [x] **PagerViewer.kt** - Added offscreenPageLimit logic for upscaling
6. [x] **WebtoonConfig.kt** - Added realCuganEnabled preference listener
7. [x] **ReaderActivity.kt** - Added waifu2x/anime4k initialization check
8. [x] **ReaderViewModel.kt** - Added ImageEnhancer.reset() on chapter start
9. [x] **HttpPageLoader.kt** - Added enhancement stream setup
10. [x] **DownloadPageLoader.kt** - Added enhancement stream setup
11. [x] **ReaderAppBars.kt** - N/A (main uses view-based bars)
12. [x] **ReaderBottomBar.kt** - N/A (main uses view-based bars)
13. [x] **ColorFilterPage.kt** - N/A (main has SettingsReaderController)
4. [x] **WebtoonViewer.kt** - Added ImageEnhancer.reprioritizeAround in onPageSelected

## Remaining (requires careful merge — NOT a simple copy-paste)

### 2. ReaderPageImageView.kt — Port full upscale integration

**Fork file:** `forks/mihon_img_upscale-master/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` (1356 lines)
**Main file:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` (369 lines)

**⚠️ DO NOT simply copy the fork file over.** The two projects have different base classes, different `setImage()` signatures, different `Config` classes, and different image decoding pipelines. The main project uses `SubsamplingScaleImageView` while the fork has a custom enhanced overlay approach. The fork's ReaderPageImageView was written for a different branch's architecture.

**What needs to be integrated into the MAIN file:**

#### A. New properties to add to the class:
```kotlin
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer

// In companion or at class level:
var currentGlobalPageIndex = 0

// Instance properties:
var enhancedImageSourceFactory: ((java.io.File) -> BufferedSource?)? = null
var suppressDefaultStatus = false
var enhancementStreamOverride: (() -> java.io.InputStream)? = null
var processedTransitionStartFraction: Float = 0f
var processedTransitionEndFraction: Float = 1f

private var enhancedBitmap: Bitmap? = null
private var mangaId: Long = -1L
private var chapterId: Long = -1L
private var pageIndex: Int = -1

private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }
```

#### B. In `onImageLoaded()` override — add enhancement polling:
After the base image is loaded, check cache and start polling for enhanced version:
```kotlin
override fun onImageLoaded() {
    super.onImageLoaded()
    // ... existing code ...
    
    // ADD: Check for enhanced image and start polling
    startEnhancementPolling()
}
```

#### C. New method: `startEnhancementPolling()`
Polls ImageEnhancementCache every ~300ms to detect when the enhanced image is ready, then swaps it in:
```kotlin
private fun startEnhancementPolling() {
    val mId = mangaId; val cId = chapterId; val pIdx = pageIndex
    if (mId == -1L || cId == -1L) return
    
    MainScope().launch {
        while (isActive) {
            ImageEnhancementCache.init(context)
            val configHash = ImageEnhancementCache.getConfigHash(
                readerPreferences.realCuganNoiseLevel().get(),
                readerPreferences.realCuganScale().get(),
                readerPreferences.realCuganInputScale().get(),
                readerPreferences.realCuganModel().get(),
                readerPreferences.realCuganMaxSizeWidth().get(),
                readerPreferences.realCuganMaxSizeHeight().get(),
                true
            )
            val pageVariant = enhancementVariantOverride ?: readerPage?.enhancementKeySuffix.orEmpty()
            val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
            if (file != null) {
                // Swap to enhanced image
                withUIContext {
                    val stream = enhancedImageSourceFactory?.invoke(file) ?: file.inputStream()
                    setImage(stream, false, imageConfig)  // Use appropriate config
                }
                break
            }
            delay(300)
        }
    }
}
```

#### D. In `onPageSelected(forward: Boolean)` override — add reprioritize:
```kotlin
override fun onPageSelected(forward: Boolean) {
    super.onPageSelected(forward)
    ImageEnhancer.reprioritizeAround(pageIndex, enhancementVariant())
}
```

#### E. In `onDetachedFromWindow()` — cancel pending requests:
```kotlin
override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    if (mangaId != -1L && chapterId != -1L) {
        ImageEnhancer.cancel(mangaId, chapterId, pageIndex, enhancementVariant())
    }
}
```

#### F. Helper: `enhancementVariant()`:
```kotlin
private fun enhancementVariant(): String {
    return enhancementVariantOverride ?: readerPage?.enhancementKeySuffix.orEmpty()
}
```

#### G. In `setImage()` — check enhancement cache before loading:
Before loading the raw stream, check if an enhanced version is cached:
```kotlin
// In setImage(), before reading streamFn():
val mId = mangaId; val cId = chapterId; val pIdx = pageIndex
if (readerPreferences.realCuganEnabled().get() && mId != -1L && cId != -1L) {
    ImageEnhancementCache.init(context)
    val configHash = ImageEnhancementCache.getConfigHash(...)
    val pageVariant = enhancementVariant()
    val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
    if (cachedFile != null) {
        // Use enhanced file instead of raw stream
        val enhancedStream = enhancedImageSourceFactory?.invoke(cachedFile) ?: cachedFile.inputStream()
        // Use enhancedStream for setImage instead of streamFn
    }
}
```

---

### 3. PagerPageHolder.kt — Add enhancement variant/stream passing

**Fork file:** `forks/mihon_img_upscale-master/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt` (~620 lines)
**Main file:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt` (~560 lines)

**⚠️ Same warning as above — DO NOT copy.** The main's PagerPageHolder has a completely different image processing pipeline (hinge gap support, shiftDoublePages, smart color backgrounds, SubsamplingScaleImageView-based zoom).

**What needs to be integrated:**

#### A. Add imports:
```kotlin
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import yokai.domain.ui.settings.ReaderPreferences
```

#### B. Add properties:
```kotlin
private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }

private var extraEnhancementWatchJob: Job? = null
private var extraEnhancementState: String? = null
```

#### C. In `init{}` — set enhancement tracking:
```kotlin
init {
    // ... existing code ...
    // Set enhancement tracking vars from fork
    refreshEnhancementTargets()
}
```

#### D. In `onPageSelected()` — add enhancement reprioritization:
```kotlin
fun onPageSelected(forward: Boolean?) {
    // ... existing code ...
    // ADD after zoom setup:
    val mId = viewer.activity.viewModel.manga?.id ?: return
    val cId = page.chapter.chapter.id ?: return
    ImageEnhancer.reprioritizeAround(page.index, page.enhancementKeySuffix)
}
```

#### E. In `onDetachedFromWindow()` — cancel enhancement jobs:
```kotlin
override fun onDetachedFromWindow() {
    // ... existing code ...
    extraEnhancementWatchJob?.cancel()
}
```

#### F. In `setImage()` — check enhancement cache:
In the main's `setImage()`, after `val (source, isAnimated) = withIOContext { ... }`, before `withUIContext {`:
```kotlin
// Check if enhanced version is available
if (!isAnimated && readerPreferences.realCuganEnabled().get()) {
    val mId = viewer.activity.viewModel.manga?.id
    val cId = page.chapter.chapter.id
    if (mId != null && cId != null) {
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true
        )
        val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, page.index, configHash, "")
        if (cachedFile != null) {
            // Replace source with enhanced version
            // Note: main uses okio.BufferedSource, fork's cachedFile returns java.io.File
            // Need to convert: okio.Buffer().readFrom(cachedFile.inputStream())
        }
    }
}
```

#### G. Add helper methods from fork:
- `currentEnhancedFile(targetPage: ReaderPage)` — looks up cache
- `currentEnhancementState(targetPage: ReaderPage)` — returns "cached"/"skipped"/"raw"/"disabled"
- `refreshEnhancementTargets()` — updates enhancementVariantOverride

---

## Implementation Strategy

**Recommended approach for items 2 & 3:**

1. Build and test the project first with the current changes (items 1, 4-10) to verify the build.gradle.kts cmake path compiles the native code successfully.

2. For ReaderPageImageView.kt (item 2), the safest approach is:
   - Read both files completely
   - Identify the specific insertion points in the main file where enhancement checks should be added
   - Add enhancement cache checks at the right lifecycle points (init, onImageLoaded, onPageSelected, onDetachedFromWindow)
   - The main's SubsamplingScaleImageView approach means we likely need to use `setProcessedSource()` or swap the image source rather than the fork's overlay approach

3. For PagerPageHolder.kt (item 3), the changes are additive:
   - Add the enhancement cache lookup in `setImage()` before the merge/split logic
   - Add reprioritization in `onPageSelected()`
   - Add job cancellation in `onDetachedFromWindow()`

4. Test with `realCuganEnabled = true` in reader settings after each change.
