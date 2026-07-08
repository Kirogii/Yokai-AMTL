package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.core.preference.PreferenceStore

/**
 * Enhancement-specific reader preferences for image upscaling and ink filter.
 */
class ReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

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
    fun joinDoublePages() = preferenceStore.getBoolean("pref_join_double_pages", false)

    fun shiftDoublePages() = preferenceStore.getBoolean("pref_shift_double_pages", false)
}
