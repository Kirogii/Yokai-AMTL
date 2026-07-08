package eu.kanade.tachiyomi.ui.reader.novel.cache

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Lightweight global cache budget enforcer for novel reader caches.
 *
 * Registered caches report their current size. When the global budget is
 * exceeded, the coordinator trims caches starting with the oldest (by
 * registration order) until the budget is satisfied.
 */
class NovelReaderCacheCoordinator(
    private val maxTotalBytes: Long,
) {
    private data class RegisteredCache(
        val reporter: NovelReaderCacheReporter,
        val registeredAtMs: Long = System.currentTimeMillis(),
    )

    private val caches = ConcurrentHashMap<String, RegisteredCache>()

    fun register(reporter: NovelReaderCacheReporter) {
        caches[reporter.cacheId()] = RegisteredCache(reporter)
        enforceBudget()
    }

    fun unregister(cacheId: String) {
        caches.remove(cacheId)
    }

    fun totalBytes(): Long = caches.values.sumOf { it.reporter.currentBytes() }

    fun enforceBudget() {
        var excess = totalBytes() - maxTotalBytes
        if (excess <= 0) return

        // Trim oldest-first (by registration time)
        val sorted = caches.values.sortedBy { it.registeredAtMs }
        for (entry in sorted) {
            if (excess <= 0) break
            val current = entry.reporter.currentBytes()
            val target = max(0L, current - excess)
            entry.reporter.trimToTargetBytes(target)
            excess = totalBytes() - maxTotalBytes
        }
    }

    fun dispose() {
        caches.values.forEach { it.reporter.dispose() }
        caches.clear()
    }
}

/**
 * Implemented by each cache that participates in the global budget.
 */
interface NovelReaderCacheReporter {
    /** Stable identifier for this cache (e.g. "chapter-disk", "translation-disk"). */
    fun cacheId(): String

    /** Current bytes used by this cache (disk or memory). */
    fun currentBytes(): Long

    /** Trim this cache to at most [targetBytes]. */
    fun trimToTargetBytes(targetBytes: Long)

    /** Release all resources. Default no-op. */
    fun dispose() {}
}
