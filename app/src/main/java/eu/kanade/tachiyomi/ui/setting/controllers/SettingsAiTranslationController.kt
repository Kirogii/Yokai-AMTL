package eu.kanade.tachiyomi.ui.setting.controllers

import eu.kanade.tachiyomi.ui.setting.SettingsComposeController
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.SettingsAiTranslationScreen

class SettingsAiTranslationController : SettingsComposeController() {
    override fun getComposableSettings(): ComposableSettings = SettingsAiTranslationScreen
}
