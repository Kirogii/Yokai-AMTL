package yokai.presentation.settings.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color as ComposeColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.injectLazy
import yokai.domain.ai.TranslationService
import yokai.domain.ui.settings.AiTranslationPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.component.preference.widget.BasePreferenceWidget
import yokai.presentation.component.preference.widget.PrefsHorizontalPadding
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.SettingsScaffold
import java.net.HttpURLConnection
import java.net.URL

object SettingsAiTranslationScreen : ComposableSettings {

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.reader

    @Composable
    override fun Content() {
        SettingsScaffold(
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
            getFontSettingsGroup(prefs),
        )
    }

    // ... (server URL, provider, openrouter, ocr, inpaint, target lang same as before) ...

    @Composable
    private fun getServerUrlAndSavePref(prefs: AiTranslationPreferences): Preference.PreferenceItem.CustomPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val serverUrl by prefs.serverUrl().collectAsState()
        var textFieldValue by remember(serverUrl) { mutableStateOf(serverUrl) }
        var verifying by remember { mutableStateOf(false) }
        var verifyResult by remember { mutableStateOf<VerifyResult?>(null) }

        val verifyAction: () -> Unit = {
            scope.launch {
                verifying = true
                verifyResult = null
                try {
                    val normalizedUrl = textFieldValue.trimEnd('/')
                    val service = TranslationService()
                    val result = service.verifyServer(normalizedUrl)
                    verifying = false
                    result.fold(
                        onSuccess = { version ->
                            prefs.serverUrl().set(normalizedUrl)
                            verifyResult = VerifyResult.Success(version)
                        },
                        onFailure = { e ->
                            verifyResult = VerifyResult.Error(e.localizedMessage ?: "Connection failed")
                        },
                    )
                } catch (e: Exception) {
                    verifying = false
                    verifyResult = VerifyResult.Error(e.localizedMessage ?: "Unknown error")
                }
            }
        }

        return Preference.PreferenceItem.CustomPreference(
            title = "FastAPI Server",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding),
            ) {
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
                                    keyboardActions = KeyboardActions(onGo = { verifyAction() }),
                                    modifier = Modifier.weight(1f),
                                    isError = verifyResult is VerifyResult.Error,
                                )
                                Button(
                                    onClick = { verifyAction() },
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

                var saving by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                if (textFieldValue != serverUrl) {
                                    prefs.serverUrl().set(textFieldValue.trimEnd('/'))
                                }
                                val service = TranslationService()
                                withContext(Dispatchers.IO) { service.syncAllSettingsToServer() }
                                withContext(Dispatchers.Main) {
                                    context.toast("Settings cached & synced to server")
                                }
                            } catch (_: Exception) {
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
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = { keyValue = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
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
                "cz" to "Chinese",
                "ru" to "Russian",
                "es" to "Spanish",
                "id" to "Indonesian",
            ),
        )
    }

    // =========================================================================
    // Font Settings with Preview
    // =========================================================================

    @Composable
    private fun getFontSettingsGroup(prefs: AiTranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "Font Settings",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "Font Family & Boldness",
                ) {
                    FontSettingsContent(prefs)
                },
            ),
        )
    }

    @Composable
    private fun FontSettingsContent(prefs: AiTranslationPreferences) {
        val scope = rememberCoroutineScope()
        val serverUrl by prefs.serverUrl().collectAsState()
        val currentWeight by prefs.fontWeight().collectAsState()
        val currentFont by prefs.fontFamily().collectAsState()

        // Font list state
        var fontList by remember { mutableStateOf<List<FontInfo>>(emptyList()) }
        var activeFont by remember(currentFont, serverUrl) { mutableStateOf(currentFont) }
        var loadingFonts by remember { mutableStateOf(true) }

        // Load fonts from server
        LaunchedEffect(serverUrl) {
            loadingFonts = true
            try {
                fontList = withContext(Dispatchers.IO) { fetchFontList(serverUrl) }
            } catch (_: Exception) {
                fontList = emptyList()
            }
            loadingFonts = false
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding),
        ) {
            Text(
                "Font Family",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (loadingFonts) {
                Text("Loading fonts...", style = MaterialTheme.typography.bodySmall)
            } else if (fontList.isEmpty()) {
                Text(
                    "No fonts found. Ensure server is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                // Horizontal scrollable font chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fontList.forEach { font ->
                        val isActive = font.filename == activeFont
                        Box(
                            modifier = Modifier
                                .border(
                                    width = if (isActive) 2.dp else 1.dp,
                                    color = if (isActive) 
                                        androidx.compose.ui.graphics.Color(0xFF28A745)
                                    else 
                                        androidx.compose.ui.graphics.Color(0xFF555555),
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .background(
                                    if (isActive) 
                                        androidx.compose.ui.graphics.Color(0x1A28A745)
                                    else 
                                        androidx.compose.ui.graphics.Color(0xFF2A2A3C),
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = font.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.clickable {
                                    activeFont = font.filename
                                    prefs.fontFamily().set(font.filename)
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            pushFontToServer(
                                                serverUrl, font.filename, currentWeight
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Font Boldness Preview
            Text(
                "Font Boldness",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val weights = listOf(
                    FontWeightLevel(0, "Thin"),
                    FontWeightLevel(1, "Light"),
                    FontWeightLevel(2, "Regular"),
                    FontWeightLevel(3, "Bold"),
                    FontWeightLevel(4, "Heavy"),
                )
                weights.forEach { level ->
                    val isSelected = level.level == currentWeight
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected)
                                    androidx.compose.ui.graphics.Color(0xFF28A745)
                                else
                                    androidx.compose.ui.graphics.Color(0xFF555555),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(4.dp)
                            .clickable {
                                prefs.fontWeight().set(level.level)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        pushFontToServer(
                                            serverUrl, activeFont, level.level
                                        )
                                    }
                                }
                            },
                    ) {
                        // Canvas preview swatch
                        Canvas(
                            modifier = Modifier.size(width = 44.dp, height = 34.dp),
                        ) {
                            drawFontSwatch(level.level)
                        }
                        Text(
                            text = "${level.level + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color(0xFF888888),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Preview shows \"Aあ\" at increasing boldness via stroke width simulation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * Draws a font weight swatch on Canvas — mirrors the extension's drawFontWeightSwatch().
     * Gray background (#8a8a8a), draws "Aあ" with stroke+fill to simulate boldness.
     */
    private fun DrawScope.drawFontSwatch(level: Int) {
        val bgColor = ComposeColor(0xFF8A8A8A)
        drawRect(color = bgColor)

        val fontSize = 16f + level * 2f
        val strokeWidth = level * 2.2f
        val text = "Aあ"

        val paint = Paint().apply {
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val cx = size.width / 2f
        val cy = size.height / 2f + fontSize / 3f

        // Stroke (white underlayer) for level > 0
        if (level > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = ComposeColor.White.toArgb()
            drawContext.canvas.nativeCanvas.drawText(text, cx, cy, paint)
        }

        // Fill (dark text on top)
        paint.style = Paint.Style.FILL
        paint.color = ComposeColor(0xFF111111).toArgb()
        drawContext.canvas.nativeCanvas.drawText(text, cx, cy, paint)
    }

    // =========================================================================
    // Server communication helpers
    // =========================================================================

    private data class FontInfo(val name: String, val filename: String, val sizeKb: Int)
    private data class FontWeightLevel(val level: Int, val label: String)

    private fun fetchFontList(serverUrl: String): List<FontInfo> {
        val url = URL("${serverUrl.trimEnd('/')}/GetFonts")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val fonts = mutableListOf<FontInfo>()
                // Parse {"fonts": [{"name":"...", "filename":"...", "size_kb":123}, ...], "count": N}
                val regex = Regex("""\{"name":"([^"]+)","filename":"([^"]+)"[^}]*?"size_kb"?:(\d+)""")
                regex.findAll(body).forEach { m ->
                    fonts.add(FontInfo(m.groupValues[1], m.groupValues[2], m.groupValues[3].toIntOrNull() ?: 0))
                }
                return fonts
            }
        } finally {
            conn.disconnect()
        }
        return emptyList()
    }

    private fun pushFontToServer(serverUrl: String, fontName: String, strokeWidth: Int) {
        val url = URL("${serverUrl.trimEnd('/')}/SetFont")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = if (fontName.isNotBlank()) {
            """{"font_path":"$fontName","stroke_width":$strokeWidth}"""
        } else {
            """{"stroke_width":$strokeWidth}"""
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private sealed class VerifyResult {
        data class Success(val version: String) : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }
}
