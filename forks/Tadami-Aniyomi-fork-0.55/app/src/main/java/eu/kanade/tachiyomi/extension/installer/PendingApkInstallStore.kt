package eu.kanade.tachiyomi.extension.installer

import android.content.Context
import android.content.Intent
import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Persisted pending APK install request used when Android requires the user to grant
 * "Install unknown apps" permission before the install intent can be launched.
 */
class PendingApkInstallStore(
    private val basePreferences: BasePreferences,
) {
    data class PendingInstall(
        val packageName: String,
        val displayName: String,
        val filePath: String,
        val kind: ApkExtensionKind,
        val backend: ApkInstallBackend,
    )

    fun save(request: PendingInstall) {
        basePreferences.pendingApkInstallPackage().set(request.packageName)
        basePreferences.pendingApkInstallDisplayName().set(request.displayName)
        basePreferences.pendingApkInstallPath().set(request.filePath)
        basePreferences.pendingApkInstallKind().set(request.kind.name)
        basePreferences.pendingApkInstallBackend().set(request.backend.name)
    }

    fun get(): PendingInstall? {
        val packageName = basePreferences.pendingApkInstallPackage().get().takeIf { it.isNotBlank() } ?: return null
        val displayName = basePreferences.pendingApkInstallDisplayName().get()
        val filePath = basePreferences.pendingApkInstallPath().get().takeIf { it.isNotBlank() } ?: return null
        val kind = basePreferences.pendingApkInstallKind().get().toEnumOrNull<ApkExtensionKind>() ?: return null
        val backend = basePreferences.pendingApkInstallBackend().get().toEnumOrNull<ApkInstallBackend>() ?: return null
        return PendingInstall(
            packageName = packageName,
            displayName = displayName,
            filePath = filePath,
            kind = kind,
            backend = backend,
        )
    }

    fun clear() {
        basePreferences.pendingApkInstallPackage().set("")
        basePreferences.pendingApkInstallDisplayName().set("")
        basePreferences.pendingApkInstallPath().set("")
        basePreferences.pendingApkInstallKind().set("")
        basePreferences.pendingApkInstallBackend().set("")
    }

    @Suppress("DEPRECATION")
    suspend fun resumeIfPermissionGranted(context: Context): Boolean {
        val pending = get() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return false
        }

        val apkFile = File(pending.filePath)
        val exists = withIOContext { apkFile.isFile }
        if (!exists) {
            logcat(LogPriority.WARN) {
                "Pending APK install file is missing package=${pending.packageName} path=${pending.filePath}"
            }
            clear()
            return false
        }

        return runCatching {
            withUIContext {
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(apkFile.getUriCompat(context), APK_MIME)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .let(context::startActivity)
            }
            logcat(LogPriority.INFO) {
                "Resumed pending APK install package=${pending.packageName} kind=${pending.kind} backend=${pending.backend}"
            }
            clear()
            true
        }.getOrElse { error ->
            logcat(LogPriority.ERROR, error) {
                "Failed to resume pending APK install package=${pending.packageName} path=${pending.filePath}"
            }
            clear()
            false
        }
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? {
        return runCatching { enumValueOf<T>(this) }.getOrNull()
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
