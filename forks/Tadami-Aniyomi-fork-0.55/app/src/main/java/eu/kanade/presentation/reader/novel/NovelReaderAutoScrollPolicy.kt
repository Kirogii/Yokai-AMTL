package eu.kanade.presentation.reader.novel

import eu.kanade.tachiyomi.ui.reader.novel.setting.NovelAutoScrollChapterEndBehavior
import kotlin.math.roundToInt

internal const val AUTO_SCROLL_PREFETCH_THRESHOLD_PERCENT = 85
internal const val AUTO_SCROLL_HANDOFF_TTL_MS = 30_000L
internal const val AUTO_SCROLL_END_DWELL_MS = 1_500L

internal enum class NovelAutoScrollMode {
    Off,
    Running,
    Cooldown,
    EndDwell,
    Handoff,
    Paused,
}

internal data class NovelAutoScrollConfig(
    val enabled: Boolean,
    val speed: Int,
    val chapterEndBehavior: NovelAutoScrollChapterEndBehavior,
    val endPauseMs: Long,
    val endOffsetPx: Int,
)

internal data class NovelAutoScrollEndState(
    val isAtEnd: Boolean,
    val stableEndFrameCount: Int,
    val shouldEnterDwell: Boolean,
    val shouldAdvanceNow: Boolean,
)

data class NovelAutoScrollHandoffState(
    val fromChapterId: Long,
    val targetChapterId: Long,
    val speed: Int,
    val requestedAtMs: Long,
)

internal fun resolveNovelAutoScrollEndState(
    canScrollForward: Boolean,
    scrollConsumedPx: Float,
    isContentReady: Boolean,
    hasCompletedInitialLayout: Boolean,
    hasRenderableItems: Boolean,
    previousStableEndFrameCount: Int,
    requiredStableFrames: Int = 2,
): NovelAutoScrollEndState {
    val canEvaluateEnd = isContentReady && hasCompletedInitialLayout && hasRenderableItems
    if (!canEvaluateEnd) {
        return NovelAutoScrollEndState(
            isAtEnd = false,
            stableEndFrameCount = 0,
            shouldEnterDwell = false,
            shouldAdvanceNow = false,
        )
    }

    val atEndThisFrame = !canScrollForward || scrollConsumedPx == 0f
    val stableFrames = if (atEndThisFrame) {
        (previousStableEndFrameCount + 1).coerceAtLeast(1)
    } else {
        0
    }
    val stableEnough = stableFrames >= requiredStableFrames.coerceAtLeast(1)
    return NovelAutoScrollEndState(
        isAtEnd = atEndThisFrame,
        stableEndFrameCount = stableFrames,
        shouldEnterDwell = stableEnough,
        shouldAdvanceNow = stableEnough,
    )
}

internal fun shouldAutoScrollAdvanceToNextChapter(
    behavior: NovelAutoScrollChapterEndBehavior,
    hasNextChapter: Boolean,
): Boolean {
    return hasNextChapter && behavior != NovelAutoScrollChapterEndBehavior.StopAtEnd
}

internal fun shouldAutoScrollContinueAcrossChapters(
    behavior: NovelAutoScrollChapterEndBehavior,
): Boolean {
    return behavior == NovelAutoScrollChapterEndBehavior.ContinuousReading
}

internal fun resolveAutoScrollPrefetchNeeded(
    currentIndex: Int,
    totalItems: Int,
    behavior: NovelAutoScrollChapterEndBehavior,
    thresholdPercent: Int = AUTO_SCROLL_PREFETCH_THRESHOLD_PERCENT,
): Boolean {
    if (behavior == NovelAutoScrollChapterEndBehavior.StopAtEnd) return false
    if (totalItems <= 0 || currentIndex < 0) return false
    val progressPercent = (((currentIndex + 1).toFloat() / totalItems.toFloat()) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
    return progressPercent >= thresholdPercent.coerceIn(1, 100)
}

internal fun resolveAutoScrollSpeedFactor(
    currentFactor: Float,
    inCooldown: Boolean,
    delta: Float,
): Float {
    val safeDelta = delta.coerceIn(0f, 1f)
    return when {
        inCooldown -> (currentFactor - safeDelta).coerceAtLeast(0f)
        currentFactor < 1f -> (currentFactor + safeDelta).coerceAtMost(1f)
        else -> 1f
    }
}

internal fun isAutoScrollHandoffExpired(
    requestedAtMs: Long,
    nowMs: Long,
    ttlMs: Long = AUTO_SCROLL_HANDOFF_TTL_MS,
): Boolean {
    return nowMs - requestedAtMs > ttlMs.coerceAtLeast(0L)
}

internal fun resolveWebViewAutoScrollNearEnd(
    totalScrollablePx: Int,
    scrollYPx: Int,
    endOffsetPx: Int,
): Boolean {
    if (totalScrollablePx <= 0) return true
    val distanceToBottomPx = (totalScrollablePx - scrollYPx).coerceAtLeast(0)
    return distanceToBottomPx <= endOffsetPx.coerceAtLeast(0)
}
