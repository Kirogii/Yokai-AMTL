package yokai.domain.ui.settings

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import yokai.i18n.MR

class ReaderPreferences(private val preferenceStore: PreferenceStore) {
    fun cutoutShort() = preferenceStore.getBoolean("cutout_short", true)

    fun pagerCutoutBehavior() = preferenceStore.getEnum(PreferenceKeys.pagerCutoutBehavior, CutoutBehaviour.IGNORE)

    fun landscapeCutoutBehavior() = preferenceStore.getEnum("landscape_cutout_behavior", LandscapeCutoutBehaviour.HIDE)

    enum class CutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        SHOW(MR.strings.start_past_cutout), // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's start past
        IGNORE(MR.strings.cutout_ignore),   // Similar to CUTOUT_MODE_DEFAULT / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    PagerConfig.CUTOUT_PAD -> CutoutBehaviour.HIDE
                    PagerConfig.CUTOUT_IGNORE -> CutoutBehaviour.IGNORE
                    else -> CutoutBehaviour.SHOW
                }
        }
    }

    enum class LandscapeCutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        DEFAULT(MR.strings.cutout_ignore),  // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    0 -> LandscapeCutoutBehaviour.HIDE
                    else -> LandscapeCutoutBehaviour.DEFAULT
                }
        }
    }

    fun webtoonDoubleTapZoomEnabled() = preferenceStore.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    fun debugMode() = preferenceStore.getBoolean("pref_enable_reader_debug_mode", BuildConfig.DEBUG)
    // === Image Upscaling (from mihon_img_upscale fork) ===
    fun waifu2xEnabled() = preferenceStore.getBoolean("pref_waifu2x_enabled", false)
    fun waifu2xNoiseLevel() = preferenceStore.getInt("pref_waifu2x_noise_level", 2)
    fun anime4kEnabled() = preferenceStore.getBoolean("pref_anime4k_enabled", false)
    fun anime4kMode() = preferenceStore.getInt("pref_anime4k_mode", 0)
    fun realCuganEnabled() = preferenceStore.getBoolean("pref_realcugan_enabled", false)
    fun realCuganNoiseLevel() = preferenceStore.getInt("pref_realcugan_noise_level", 0)
    fun realCuganScale() = preferenceStore.getInt("pref_realcugan_scale", 2)
    fun realCuganInputScale() = preferenceStore.getInt("pref_realcugan_input_scale", 100)
    fun realCuganModel() = preferenceStore.getInt("pref_realcugan_model", 0)
    fun realCuganPreloadSize() = preferenceStore.getInt("pref_realcugan_preload_size", 3)
    fun realCuganProEnabled() = preferenceStore.getBoolean("pref_realcugan_pro_enabled", false)
    fun realCuganPerformanceMode() = preferenceStore.getInt("pref_realcugan_performance_mode", 0)
    fun realCuganMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_max_size_width", 1600)
    fun realCuganMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_max_size_height", 1600)
    fun realCuganResizeLargeImage() = preferenceStore.getBoolean("pref_realcugan_resize_large_image", true)
    fun realCuganShowStatus() = preferenceStore.getBoolean("pref_realcugan_show_status", false)
}
