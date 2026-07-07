package yokai.domain.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.ui.settings.AiTranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles communication with the FastAPI translation server.
 * Mirrors the extension's background.js 3-step translation flow:
 *   1. POST /v1/translate (multipart form-data) → job_id
 *   2. GET /v1/translate/{job_id} poll for status
 *   3. POST /v1/translate/{job_id}/image → rendered PNG
 */
class TranslationService {

    private val prefs: AiTranslationPreferences = Injekt.get()

    /** Verify server connectivity by hitting /version */
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
     * Translates a single bitmap image using the 3-step job flow:
     *   1. POST /v1/translate with multipart form-data
     *   2. Poll GET /v1/translate/{job_id} until completed/failed
     *   3. POST /v1/translate/{job_id}/image to get the rendered PNG
     */
    suspend fun translateImage(
        bitmap: Bitmap,
        ocrLang: String = prefs.ocrSourceLang().get(),
        targetLang: String = prefs.targetLanguage().get(),
        inpaint: Boolean = true,
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val serverUrl = prefs.serverUrl().get().trimEnd('/')

            // Step 1: Upload image as multipart form-data
            val jobId = createTranslationJob(serverUrl, bitmap, targetLang, ocrLang, inpaint)
                ?: return@withContext Result.failure(Exception("Failed to create translation job"))

            // Step 2: Poll for completion
            val completed = pollJobStatus(serverUrl, jobId)
            if (!completed) {
                return@withContext Result.failure(Exception("Translation job $jobId failed or timed out"))
            }

            // Step 3: Fetch the rendered translated image
            val translatedBitmap = fetchTranslatedImage(serverUrl, jobId)
                ?: return@withContext Result.failure(Exception("Failed to fetch translated image"))

            Result.success(translatedBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 1: POST /v1/translate with multipart/form-data
     * Matches the extension's FormData: image (file), target_lang, ocr_lang, inpaint
     * Returns job_id string, or null on failure.
     */
    private fun createTranslationJob(
        serverUrl: String,
        bitmap: Bitmap,
        targetLang: String,
        ocrLang: String,
        inpaint: Boolean = true,
    ): String? {
        // Compress bitmap to JPEG bytes for upload
        val imageBytes = ByteArrayOutputStream().use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
            os.toByteArray()
        }

        val boundary = "MangaAMTL-${System.currentTimeMillis()}"
        val connection = URL("$serverUrl/v1/translate").openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        connection.outputStream.use { os ->
            // -- image file part
            writeFormField(os, boundary, "image", "manga_page.jpg", "image/jpeg", imageBytes)
            // -- target_lang
            writeFormField(os, boundary, "target_lang", targetLang)
            // -- ocr_lang
            writeFormField(os, boundary, "ocr_lang", ocrLang)
            // -- inpaint
            writeFormField(os, boundary, "inpaint", if (inpaint) "true" else "false")
            // closing boundary
            os.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = connection.responseCode
        if (code == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            return extractJsonString(response, "job_id")
        }
        connection.disconnect()
        return null
    }

    /**
     * Step 2: Poll GET /v1/translate/{job_id} until completed or failed.
     * Returns true if completed successfully.
     */
    private suspend fun pollJobStatus(serverUrl: String, jobId: String): Boolean {
        var attempts = 0
        val maxAttempts = 60 // ~2 minutes max

        while (attempts < maxAttempts) {
            delay(2000) // wait 2s between polls, matching extension's setTimeout
            attempts++

            try {
                val connection = URL("$serverUrl/v1/translate/$jobId").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                val code = connection.responseCode
                if (code == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()

                    val status = extractJsonString(body, "status")
                    when (status) {
                        "completed" -> return true
                        "failed" -> return false
                        // "pending" or "processing" → keep polling
                    }
                } else {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Network glitch, keep trying
            }
        }
        return false
    }

    /**
     * Step 3: POST /v1/translate/{job_id}/image → rendered PNG bytes → Bitmap
     */
    private fun fetchTranslatedImage(serverUrl: String, jobId: String): Bitmap? {
        val connection = URL("$serverUrl/v1/translate/$jobId/image").openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.requestMethod = "POST"

        val code = connection.responseCode
        if (code == 200) {
            val bytes = connection.inputStream.readBytes()
            connection.disconnect()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        connection.disconnect()
        return null
    }

    // =========================================================================
    // Server config push endpoints (sync settings to backend)
    // =========================================================================

    suspend fun pushOcrMode(mode: String) = postToServer("/SetOcrMode", """{"mode":"$mode"}""")

    suspend fun pushInpaintMode(mode: String) = postToServer("/SetInpaintMode", """{"mode":"$mode"}""")

    suspend fun pushModelType(modelType: String, model: String = "", apiKey: String = "") {
        val body = buildString {
            append("{\"model_type\":\"$modelType\"")
            if (model.isNotBlank()) append(",\"model\":\"$model\"")
            if (apiKey.isNotBlank()) append(",\"api_key\":\"$apiKey\"")
            append("}")
        }
        postToServer("/SetModelType", body)
    }

    suspend fun pushFontSettings(strokeWidth: Int, fontName: String = "") {
        val body = buildString {
            append("{")
            append("\"stroke_width\":$strokeWidth")
            if (fontName.isNotBlank()) append(",\"font_name\":\"$fontName\"")
            append("}")
        }
        postToServer("/SetFont", body)
    }

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

    // =========================================================================
    // Helpers
    // =========================================================================

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
            connection.responseCode // trigger request
            connection.disconnect()
        } catch (_: Exception) {
            // Best-effort sync
        }
    }

    private fun writeFormField(
        os: OutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"$name\"\r\n")
        sb.append("\r\n")
        sb.append(value)
        sb.append("\r\n")
        os.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeFormField(
        os: OutputStream,
        boundary: String,
        name: String,
        filename: String,
        contentType: String,
        data: ByteArray,
    ) {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("\r\n")
        os.write(sb.toString().toByteArray(Charsets.UTF_8))
        os.write(data)
        os.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun extractVersion(body: String): String {
        val jsonMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)
        val plainMatch = Regex("\"([^\"]+)\"").find(body)
        return jsonMatch?.groupValues?.get(1) ?: plainMatch?.groupValues?.get(1) ?: body.trim().take(20)
    }

    private fun extractJsonString(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
        return match?.groupValues?.get(1)
    }
}
