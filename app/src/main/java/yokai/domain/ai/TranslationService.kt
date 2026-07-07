package yokai.domain.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.ui.settings.AiTranslationPreferences
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles communication with the FastAPI translation server.
 * Mirrors the extension's background.js content.js translation flow.
 */
class TranslationService {

    private val prefs: AiTranslationPreferences = Injekt.get()

    /**
     * Verifies server connectivity by hitting /version
     */
    suspend fun verifyServer(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${url.trimEnd('/')}/version").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val code = connection.responseCode
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                val version = extractVersion(body)
                connection.disconnect()
                Result.success(version)
            } else {
                connection.disconnect()
                Result.failure(Exception("HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Translates a single bitmap image.
     * Returns the translated bitmap, or null on failure.
     */
    suspend fun translateImage(
        bitmap: Bitmap,
        ocrLang: String = prefs.ocrSourceLang().get(),
        targetLang: String = prefs.targetLanguage().get(),
        colorize: Boolean = prefs.colorize().get(),
        ocrMode: String = prefs.ocrEngine().get(),
        inpaintMode: String = prefs.inpaintMode().get(),
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val serverUrl = prefs.serverUrl().get().trimEnd('/')
            val modelType = prefs.translationProvider().get()

            // Convert bitmap to base64 JPEG (smaller than PNG for upload)
            val byteStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteStream)
            val base64Image = Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP)

            // Build request body — matches the extension's /v1/translate format
            val requestBody = buildString {
                append("{")
                append("\"image_b64\":\"$base64Image\",")
                append("\"ocr_lang\":\"$ocrLang\",")
                append("\"target_lang\":\"$targetLang\",")
                append("\"colorize\":$colorize,")
                append("\"ocr_mode\":\"$ocrMode\",")
                append("\"inpaint_mode\":\"$inpaintMode\",")
                append("\"model_type\":\"$modelType\"")
                if (modelType == "openrouter") {
                    val model = prefs.openrouterModel().get()
                    val apiKey = prefs.openrouterApiKey().get()
                    if (model.isNotBlank()) {
                        append(",\"openrouter_model\":\"$model\"")
                    }
                    if (apiKey.isNotBlank()) {
                        append(",\"openrouter_api_key\":\"$apiKey\"")
                    }
                }
                append("}")
            }

            val connection = URL("$serverUrl/v1/translate").openConnection() as HttpURLConnection
            connection.connectTimeout = 120000  // translation can take time
            connection.readTimeout = 120000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Parse response: {"image_b64": "..."} or {"result_b64": "..."}
                val base64Result = extractJsonString(response, "image_b64")
                    ?: extractJsonString(response, "result_b64")
                    ?: return@withContext Result.failure(Exception("No translated image in response"))

                val decodedBytes = Base64.decode(base64Result, Base64.DEFAULT)
                val translatedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ?: return@withContext Result.failure(Exception("Failed to decode translated image"))

                Result.success(translatedBitmap)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                connection.disconnect()
                Result.failure(Exception("Server returned $code: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Push OCR mode to server (syncs with /SetOcrMode)
     */
    suspend fun pushOcrMode(mode: String) = postToServer("/SetOcrMode", """{"mode":"$mode"}""")

    /**
     * Push inpainting mode to server (syncs with /SetInpaintMode)
     */
    suspend fun pushInpaintMode(mode: String) = postToServer("/SetInpaintMode", """{"mode":"$mode"}""")

    /**
     * Push model type to server (syncs with /SetModelType)
     */
    suspend fun pushModelType(modelType: String, model: String = "", apiKey: String = "") {
        val body = buildString {
            append("{\"model_type\":\"$modelType\"")
            if (model.isNotBlank()) append(",\"model\":\"$model\"")
            if (apiKey.isNotBlank()) append(",\"api_key\":\"$apiKey\"")
            append("}")
        }
        postToServer("/SetModelType", body)
    }

    /**
     * Push font settings to server (syncs with /SetFont)
     */
    suspend fun pushFontSettings(strokeWidth: Int, fontName: String = "") {
        val body = buildString {
            append("{")
            append("\"stroke_width\":$strokeWidth")
            if (fontName.isNotBlank()) append(",\"font_name\":\"$fontName\"")
            append("}")
        }
        postToServer("/SetFont", body)
    }

    /**
     * Syncs all cached settings to the server.
     * Called when user hits "Save Settings".
     */
    suspend fun syncAllSettingsToServer() = withContext(Dispatchers.IO) {
        val serverUrl = prefs.serverUrl().get().trimEnd('/')
        if (serverUrl.isBlank()) return@withContext

        val ocrMode = prefs.ocrEngine().get()
        val inpaintMode = prefs.inpaintMode().get()
        val modelType = prefs.translationProvider().get()
        val fontWeight = prefs.fontWeight().get()
        val fontFamily = prefs.fontFamily().get()

        pushOcrMode(ocrMode)
        pushInpaintMode(inpaintMode)
        pushFontSettings(fontWeight, fontFamily)

        when (modelType) {
            "openrouter" -> {
                val model = prefs.openrouterModel().get()
                val key = prefs.openrouterApiKey().get()
                pushModelType("openrouter", model, key)
            }
            else -> pushModelType("local")
        }
    }

    private suspend fun postToServer(endpoint: String, body: String) {
        val serverUrl = prefs.serverUrl().get().trimEnd('/')
        if (serverUrl.isBlank()) return
        try {
            val connection = URL("$serverUrl$endpoint").openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode // trigger the request
            connection.disconnect()
        } catch (_: Exception) {
            // Best-effort sync; don't crash if server is unavailable
        }
    }

    private fun extractVersion(body: String): String {
        val jsonMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)
        val plainMatch = Regex("\"([^\"]+)\"").find(body)
        return jsonMatch?.groupValues?.get(1) ?: plainMatch?.groupValues?.get(1) ?: body.trim().take(20)
    }

    private fun extractJsonString(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
        // Handle base64 which may contain unescaped chars; grab until closing quote
        return match?.groupValues?.get(1)
    }
}
