package yokai.presentation.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import yokai.domain.ai.TranslationService
import yokai.domain.ui.settings.AiTranslationPreferences
import yokai.presentation.component.preference.Preference
import yokai.presentation.component.preference.widget.BasePreferenceWidget
import yokai.presentation.component.preference.widget.PrefsHorizontalPadding
import yokai.presentation.settings.ComposableSettings

/**
 * AI Translation settings screen, intended as a standalone settings page
 * accessible from reader settings menu.
 *
 * Features:
 * - Server URL + Verify ("Set") button at top
 * - Provider (Local/OpenRouter), OCR Engine, Inpainting Mode, Target Language dropdowns
 * - OpenRouter model/API key fields (shown when OpenRouter selected)
 * - "Cache & Sync Settings" save button that pushes all settings to the server
 */
object SettingsAiTranslationScreen : ComposableSettings {

    @Composable
    override fun getTitleRes(): StringResource {
        return object : StringResource {
            override val resourceId: String = "ai_translation"
        }
    }

    @Composable
    override fun Content() {
        yokai.presentation.settings.SettingsScaffold(
            title = "AI Translation",
            itemsProvider = { getPreferences() },
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs: AiTranslationPreferences by injectLazy()

        return persistentListOf(
            getServerUrlAndSavePref(prefs),
            getTranslationProviderPref(prefs),
            getOpenRouterGroup(prefs),
            getOcrEnginePref(prefs),
            getInpaintModePref(prefs),
            getTargetLanguagePref(prefs),
        )
    }

    @Composable
    private fun getServerUrlAndSavePref(prefs: AiTranslationPreferences): Preference.PreferenceItem.CustomPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val serverUrl by prefs.serverUrl().collectAsState()
        var textFieldValue by remember(serverUrl) { mutableStateOf(serverUrl) }
        var verifying by remember { mutableStateOf(false) }
        var verifyResult by remember { mutableStateOf<VerifyResult?>(null) }

        return Preference.PreferenceItem.CustomPreference(
            title = "FastAPI Server",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Server URL + Set button
                BasePreferenceWidget(
                    subcomponent = {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = textFieldValue,
                                    onValueChange = { textFieldValue = it },
                                    label = { Text("Server URL") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            verifyAndSaveServer(prefs, textFieldValue, scope) { verifying = it; verifyResult = null } { verifyResult = it }
                                        },
                                    ),
                                    modifier = Modifier.weight(1f),
                                    isError = verifyResult is VerifyResult.Error,
                                )
                                Button(
                                    onClick = {
                                        verifyAndSaveServer(prefs, textFieldValue, scope) { verifying = it; verifyResult = null } { verifyResult = it }
                                    },
                                    enabled = !verifying,
                                ) {
                                    if (verifying) Text("...") else Text("Set")
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            when (val result = verifyResult) {
                                is VerifyResult.Success -> Text(
                                    "Connected — server v${result.version}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is VerifyResult.Error -> Text(
                                    result.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                null -> {}
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Cache & Sync button
                var saving by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                // First save the URL if changed
                                if (textFieldValue != serverUrl) {
                                    prefs.serverUrl().set(textFieldValue.trimEnd('/'))
                                }
                                val service: TranslationService = uy.kohesive.injekt.Injekt.get()
                                withContext(Dispatchers.IO) { service.syncAllSettingsToServer() }
                                withContext(Dispatchers.Main) {
                                    context.toast("Settings cached & synced to server")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    context.toast("Synced to server (partial — check server)")
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        if (saving) "Saving & Syncing..." else "Cache & Sync Settings",
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun getTranslationProviderPref(prefs: AiTranslationPreferences): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            pref = prefs.translationProvider(),
            title = "Translation Provider",
            entries = persistentMapOf(
                "local" to "Local (GGUF)",
                "openrouter" to "OpenRouter (Cloud)",
            ),
        )
    }

    @Composable
    private fun getOpenRouterGroup(prefs: AiTranslationPreferences): Preference.PreferenceGroup {
        val provider by prefs.translationProvider().collectAsState()

        if (provider != "openrouter") {
            return Preference.PreferenceGroup(
                title = "OpenRouter",
                enabled = false,
                preferenceItems = persistentListOf(),
            )
        }

        val model by prefs.openrouterModel().collectAsState()
        val apiKey by prefs.openrouterApiKey().collectAsState()

        return Preference.PreferenceGroup(
            title = "OpenRouter",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "Model & API Key",
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PrefsHorizontalPadding),
                    ) {
                        var modelValue by remember(model) { mutableStateOf(model) }
                        var keyValue by remember(apiKey) { mutableStateOf(apiKey) }

                        OutlinedTextField(
                            value = modelValue,
                            onValueChange = { modelValue = it },
                            label = { Text("Model ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. openai/gpt-4o-mini") },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = { keyValue = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("sk-or-...") },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                prefs.openrouterModel().set(modelValue.trim())
                                if (keyValue.isNotBlank()) {
                                    prefs.openrouterApiKey().set(keyValue.trim())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                            ),
                        ) {
                            Text("Save OpenRouter Config")
                        }
                    }
                },
            ),
        )
    }

    @Composable
    private fun getOcrEnginePref(prefs: AiTranslationPreferences): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            pref = prefs.ocrEngine(),
            title = "OCR Engine",
            entries = persistentMapOf(
                "glm" to "GLM (Local, Korean)",
                "hayai" to "Hayai (Local, Japanese)",
                "lens" to "Google Lens (Cloud, All)",
            ),
        )
    }

    @Composable
    private fun getInpaintModePref(prefs: AiTranslationPreferences): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            pref = prefs.inpaintMode(),
            title = "Inpainting Mode",
            entries = persistentMapOf(
                "low" to "Low (Fast)",
                "high" to "High (Quality)",
            ),
        )
    }

    @Composable
    private fun getTargetLanguagePref(prefs: AiTranslationPreferences): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            pref = prefs.targetLanguage(),
            title = "Target Language",
            entries = persistentMapOf(
                "en" to "English",
                "ja" to "Japanese",
                "ko" to "Korean",
                "zh" to "Chinese",
                "ru" to "Russian",
                "es" to "Spanish",
                "id" to "Indonesian",
            ),
        )
    }

    private fun verifyAndSaveServer(
        prefs: AiTranslationPreferences,
        url: String,
        scope: kotlinx.coroutines.CoroutineScope,
        onStart: (Boolean) -> Unit,
        onResult: (VerifyResult) -> Unit,
    ) {
        scope.launch {
            onStart(true)
            try {
                val normalization = url.trimEnd('/')
                val service: TranslationService = uy.kohesive.injekt.Injekt.get()
                val result = service.verifyServer(normalization)
                onStart(false)
                result.fold(
                    onSuccess = { version ->
                        prefs.serverUrl().set(normalization)
                        onResult(VerifyResult.Success(version))
                    },
                    onFailure = { e ->
                        onResult(VerifyResult.Error(e.localizedMessage ?: "Connection failed"))
                    },
                )
            } catch (e: Exception) {
                onStart(false)
                onResult(VerifyResult.Error(e.localizedMessage ?: "Unknown error"))
            }
        }
    }

    private sealed class VerifyResult {
        data class Success(val version: String) : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }
}
