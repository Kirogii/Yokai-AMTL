package eu.kanade.domain.extension.novel.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extensionrepo.novel.repository.NovelExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet

class TrustNovelExtension(
    private val novelExtensionRepoRepository: NovelExtensionRepoRepository,
    private val preferences: SourcePreferences,
) {
    suspend fun getTrustedFingerprints(): Set<String> {
        return novelExtensionRepoRepository.getAll()
            .map { it.signingKeyFingerprint }
            .toHashSet()
    }

    fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>, trustedFingerprints: Set<String>): Boolean {
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions().get()
    }

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        return isTrusted(pkgInfo, fingerprints, getTrustedFingerprints())
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions().getAndSet { exts ->
            exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet().also {
                it += "$pkgName:$versionCode:$signatureHash"
            }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions().delete()
    }
}
