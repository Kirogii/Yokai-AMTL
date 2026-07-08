@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.presentation.reader.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.novel.setting.NovelReaderOverride
import eu.kanade.tachiyomi.ui.reader.novel.setting.NovelReaderPreferences
import eu.kanade.tachiyomi.ui.reader.novel.setting.NovelTtsHighlightMode
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

@Composable
fun NovelReaderSettingsDialog(
    sourceId: Long,
    currentWebViewActive: Boolean,
    currentPageReaderActive: Boolean,
    onDismissRequest: () -> Unit,
) {
    val preferences = remember { Injekt.get<NovelReaderPreferences>() }
    val sourceOverrides = remember { preferences.sourceOverrides() }
    val overrides by sourceOverrides.changes().collectAsStateWithLifecycle(initialValue = sourceOverrides.get())
    val overrideEnabled = overrides[sourceId] != null
    val settingsFlow = remember(sourceId) { preferences.settingsFlow(sourceId) }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = preferences.resolveSettings(sourceId))

    val tabTitles = persistentListOf(
        stringResource(AYMR.strings.novel_reader_tab_general),
        stringResource(AYMR.strings.novel_reader_tab_reading),
    )

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = tabTitles,
        enableSwipeDismiss = false,
        modifier = Modifier.fillMaxHeight(0.7f),
    ) { page ->
        when (page) {
            0 -> {
                GeneralTab(
                    settings = settings,
                    sourceId = sourceId,
                    currentWebViewActive = currentWebViewActive,
                    currentPageReaderActive = currentPageReaderActive,
                    overrideEnabled = overrideEnabled,
                    preferences = preferences,
                    onDismissRequest = onDismissRequest,
                )
            }
            else -> {
                ReadingTab(
                    settings = settings,
                    sourceId = sourceId,
                    overrideEnabled = overrideEnabled,
                    preferences = preferences,
                )
            }
        }
    }
}

@Composable
fun NovelReaderTtsBehaviorSettingsDialog(
    sourceId: Long,
    onDismissRequest: () -> Unit,
) {
    val preferences = remember { Injekt.get<NovelReaderPreferences>() }
    val sourceOverrides = remember { preferences.sourceOverrides() }
    val overrides by sourceOverrides.changes().collectAsStateWithLifecycle(initialValue = sourceOverrides.get())
    val overrideEnabled = overrides[sourceId] != null
    val settingsFlow = remember(sourceId) { preferences.settingsFlow(sourceId) }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = preferences.resolveSettings(sourceId))

    fun <T> update(
        value: T,
        copyOverride: (NovelReaderOverride, T) -> NovelReaderOverride,
        setGlobal: (T) -> Unit,
    ) {
        if (overrideEnabled) {
            preferences.updateSourceOverride(sourceId) { copyOverride(it, value) }
        } else {
            setGlobal(value)
        }
    }

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(stringResource(AYMR.strings.novel_reader_tts_behavior_settings)),
        enableSwipeDismiss = false,
        modifier = Modifier.fillMaxHeight(0.7f),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LnReaderSliderRow(
                label = stringResource(AYMR.strings.novel_reader_tts_speech_rate),
                valueText = { formatTtsPercentage(it / 100f) },
                committedValue = settings.ttsSpeechRate * 100f,
                range = 50f..200f,
                steps = 149,
                enabled = true,
                onCommit = {
                    update(
                        it / 100f,
                        { o, v -> o.copy(ttsSpeechRate = v) },
                        { preferences.ttsSpeechRate().set(it) },
                    )
                },
            )
            LnReaderSliderRow(
                label = stringResource(AYMR.strings.novel_reader_tts_pitch),
                valueText = { formatTtsPercentage(it / 100f) },
                committedValue = settings.ttsPitch * 100f,
                range = 50f..200f,
                steps = 149,
                enabled = true,
                onCommit = {
                    update(
                        it / 100f,
                        { o, v -> o.copy(ttsPitch = v) },
                        { preferences.ttsPitch().set(it) },
                    )
                },
            )
            ListPreferenceWidget(
                value = settings.ttsHighlightMode,
                title = stringResource(AYMR.strings.novel_reader_tts_highlight_mode),
                subtitle = stringResource(AYMR.strings.novel_reader_tts_highlight_mode_summary),
                icon = null,
                entries = NovelTtsHighlightMode.entries.associateWith { getTtsHighlightModeLabel(it) },
                onValueChange = {
                    update(
                        it,
                        { o, v -> o.copy(ttsHighlightMode = v) },
                        { preferences.ttsHighlightMode().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_word_highlight_enabled),
                subtitle = stringResource(AYMR.strings.novel_reader_tts_word_highlight_enabled_summary),
                checked = settings.ttsWordHighlightEnabled,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsWordHighlightEnabled = v) },
                        { preferences.ttsWordHighlightEnabled().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_auto_advance_chapter),
                checked = settings.ttsAutoAdvanceChapter,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsAutoAdvanceChapter = v) },
                        { preferences.ttsAutoAdvanceChapter().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_follow_along),
                subtitle = stringResource(AYMR.strings.novel_reader_tts_follow_along_summary),
                checked = settings.ttsFollowAlong,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsFollowAlong = v) },
                        { preferences.ttsFollowAlong().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_pause_on_manual_navigation),
                checked = settings.ttsPauseOnManualNavigation,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsPauseOnManualNavigation = v) },
                        { preferences.ttsPauseOnManualNavigation().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_keep_screen_on_during_playback),
                checked = settings.ttsKeepScreenOnDuringPlayback,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsKeepScreenOnDuringPlayback = v) },
                        { preferences.ttsKeepScreenOnDuringPlayback().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_prefer_translated_text),
                subtitle = stringResource(AYMR.strings.novel_reader_tts_prefer_translated_text_summary),
                checked = settings.ttsPreferTranslatedText,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsPreferTranslatedText = v) },
                        { preferences.ttsPreferTranslatedText().set(it) },
                    )
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(AYMR.strings.novel_reader_tts_read_chapter_title),
                checked = settings.ttsReadChapterTitle,
                onCheckedChanged = {
                    update(
                        it,
                        { o, v -> o.copy(ttsReadChapterTitle = v) },
                        { preferences.ttsReadChapterTitle().set(it) },
                    )
                },
            )
        }
    }
}

@Composable
private fun getTtsHighlightModeLabel(mode: NovelTtsHighlightMode): String {
    return when (mode) {
        NovelTtsHighlightMode.AUTO -> stringResource(AYMR.strings.novel_reader_tts_highlight_mode_auto)
        NovelTtsHighlightMode.EXACT -> stringResource(AYMR.strings.novel_reader_tts_highlight_mode_exact)
        NovelTtsHighlightMode.ESTIMATED -> stringResource(AYMR.strings.novel_reader_tts_highlight_mode_estimated)
        NovelTtsHighlightMode.OFF -> stringResource(AYMR.strings.novel_reader_tts_highlight_mode_off)
    }
}

private fun formatTtsPercentage(value: Float): String {
    return "${(value * 100).roundToInt()}%"
}
