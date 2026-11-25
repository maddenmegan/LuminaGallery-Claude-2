package com.lumina.gallery.ai

import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface GeminiResult {
    data class Ok(val text: String, val model: String) : GeminiResult
    data class Fail(val errors: List<String>) : GeminiResult
}

object Ocr {
    val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")

    private const val PROMPT =
        "Extract ALL visible text from this image exactly as written. Keep the original script and " +
            "language (Bangla, Arabic, English or any other) and keep line breaks. Do NOT translate, " +
            "transliterate, or convert any script into another script. Output only the extracted text."

    /** Instant on-device OCR (ML Kit Latin model: English and other Latin-script text). */
    suspend fun offline(bmp: Bitmap): String =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bmp, 0)).await().text

    fun nonLatinShare(s: String): Float {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 1f
        return letters.count { it.code > 0x024F }.toFloat() / letters.length
    }

    /**
     * Gemini Vision with an explicit fallback chain. Every failure is recorded with the exact model and
     * HTTP status / message; nothing silently falls back to another OCR server.
     */
    suspend fun gemini(bmp: Bitmap, key: String): GeminiResult = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext GeminiResult.Fail(listOf("No Gemini API key saved. Add one in ⋮ → AI OCR Language Key."))
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray()
                            .put(JSONObject().put("text", PROMPT))
                            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                    )
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0))
            .toString().toByteArray()

        val errors = ArrayList<String>()
        for (model in MODELS) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 90000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val json = JSONObject(resp)
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) return@withContext GeminiResult.Ok(text, model)
                    val block = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    val finish = json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
                    errors.add("$model → HTTP 200 but empty text (blockReason='$block', finishReason='$finish')")
                } else {
                    val msg = try {
                        JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp.take(300)
                    } catch (e: Exception) {
                        resp.take(300)
                    }
                    errors.add("$model → HTTP $code: $msg")
                    if ((code == 400 || code == 401 || code == 403) && msg.contains("API key", true)) {
                        return@withContext GeminiResult.Fail(errors)   // key is wrong: other models will fail identically
                    }
                }
            } catch (e: Exception) {
                errors.add("$model → ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        GeminiResult.Fail(errors)
    }
}
