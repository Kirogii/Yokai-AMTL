package yokai.domain.ui.settings

import eu.kanade.tachiyomi.core.preference.PreferenceStore

/**
 * Preferences for AI Translation feature.
 * All settings are cached via PreferenceStore (persistent).
 * Mirrors the extension's chrome.storage.local caching pattern.
 */
class AiTranslationPreferences(private val preferenceStore: PreferenceStore) {

    // API Server
    fun serverUrl() = preferenceStore.getString("ai_translation_server_url", "http://localhost:8000")

    // Translation Provider: local / openrouter
    fun translationProvider() = preferenceStore.getString("ai_translation_provider", "local")

    // OpenRouter model ID (e.g. "openai/gpt-4o-mini")
    fun openrouterModel() = preferenceStore.getString("ai_translation_openrouter_model", "")

    // OpenRouter API key
    fun openrouterApiKey() = preferenceStore.getString(
        eu.kanade.tachiyomi.core.preference.Preference.privateKey("ai_translation_openrouter_key"),
        "",
    )

    // Inpainting Mode: low / high
    fun inpaintMode() = preferenceStore.getString("ai_translation_inpaint_mode", "low")

    // OCR Engine: glm / hayai / lens
    fun ocrEngine() = preferenceStore.getString("ai_translation_ocr_engine", "glm")

    // OCR Source Language (defaults to Japanese)
    fun ocrSourceLang() = preferenceStore.getString("ai_translation_ocr_lang", "ja")

    // Target Language for translation
    fun targetLanguage() = preferenceStore.getString("ai_translation_target_lang", "en")

    // Font stroke width (0-4, default 2 = Regular)
    fun fontWeight() = preferenceStore.getInt("ai_translation_font_weight", 2)

    // Font family filename
    fun fontFamily() = preferenceStore.getString("ai_translation_font_family", "")

    // Whether to colorize translated text
    fun colorize() = preferenceStore.getBoolean("ai_translation_colorize", true)
}
