package eu.kanade.tachiyomi.extension.installer

sealed class ApkInstallFailure {
    data object PermissionMissing : ApkInstallFailure()
    data object PackageInstallerTimeout : ApkInstallFailure()
    data object PackageInstallerAborted : ApkInstallFailure()
    data object DownloadFailed : ApkInstallFailure()
    data object MissingApkFile : ApkInstallFailure()
    data class Unknown(val message: String?) : ApkInstallFailure()
}
