package eu.kanade.domain.base

import android.content.Context
import android.content.pm.PackageManager
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.GLUtil
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR

class BasePreferences(
    val context: Context,
    private val preferenceStore: PreferenceStore,
) {

    fun downloadedOnly() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_downloaded_only"),
        false,
    )

    fun incognitoMode() = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)

    fun extensionInstaller() = ExtensionInstallerPreference(context, preferenceStore)

    fun pendingApkInstallPackage() = preferenceStore.getString(
        Preference.appStateKey("pending_apk_install_package"),
        "",
    )

    fun pendingApkInstallDisplayName() = preferenceStore.getString(
        Preference.appStateKey("pending_apk_install_display_name"),
        "",
    )

    fun pendingApkInstallPath() = preferenceStore.getString(Preference.appStateKey("pending_apk_install_path"), "")

    fun pendingApkInstallKind() = preferenceStore.getString(Preference.appStateKey("pending_apk_install_kind"), "")

    fun pendingApkInstallBackend() = preferenceStore.getString(
        Preference.appStateKey("pending_apk_install_backend"),
        "",
    )

    fun lastExtensionApkPackage() = preferenceStore.getString(Preference.appStateKey("last_extension_apk_package"), "")

    fun lastExtensionApkDisplayName() = preferenceStore.getString(
        Preference.appStateKey("last_extension_apk_display_name"),
        "",
    )

    fun lastExtensionApkPath() = preferenceStore.getString(Preference.appStateKey("last_extension_apk_path"), "")

    fun lastExtensionApkKind() = preferenceStore.getString(Preference.appStateKey("last_extension_apk_kind"), "")

    fun deviceHasPip() = context.packageManager.hasSystemFeature(
        PackageManager.FEATURE_PICTURE_IN_PICTURE,
    )

    fun shownOnboardingFlow() = preferenceStore.getBoolean(Preference.appStateKey("onboarding_complete"), false)

    enum class ExtensionInstaller(val titleRes: StringResource, val requiresSystemPermission: Boolean) {
        LEGACY(MR.strings.ext_installer_legacy, true),
        PACKAGEINSTALLER(MR.strings.ext_installer_packageinstaller, true),
        SHIZUKU(MR.strings.ext_installer_shizuku, false),
        PRIVATE(MR.strings.ext_installer_private, false),
    }

    fun displayProfile() = preferenceStore.getString("pref_display_profile_key", "")

    fun hardwareBitmapThreshold() = preferenceStore.getInt("pref_hardware_bitmap_threshold", GLUtil.SAFE_TEXTURE_LIMIT)

    fun alwaysDecodeLongStripWithSSIV() = preferenceStore.getBoolean("pref_always_decode_long_strip_with_ssiv", false)
}
