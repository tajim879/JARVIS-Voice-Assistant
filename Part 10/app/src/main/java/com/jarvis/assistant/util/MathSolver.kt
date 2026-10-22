package com.jarvis.assistant.util

import android.graphics.Bitmap
import android.util.Base64
import com.jarvis.assistant.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Step-by-step math solving with Gemini (text and/or a photo of the problem).
 * Uses the Gemini API key saved in Settings.
 */
object MathSolver {

    class Solution(val text: String, val error: String?, val detail: String?)

    private const val MAX_IMAGE_SIDE = 1600

    suspend fun solve(problem: String, image: Bitmap?, topic: String): Solution {
        val jpeg = if (image != null) encodeJpeg(image) else null
        return solveJpeg(problem, jpeg, topic)
    }

    suspend fun solveJpeg(problem: String, jpeg: ByteArray?, topic: String): Solution {
        val apiKey = try { JarvisApp.instance.preferences.apiKey } catch (e: Exception) { "" }
        val language = Locale.getDefault().displayLanguage.ifBlank { "English" }

        val instruction =
            "You are an expert math tutor and exact solver. You can solve every kind of math: arithmetic, " +
                "fractions, percentages, algebra, equations and inequalities, geometry, trigonometry, logarithms, " +
                "calculus (limits, derivatives, integrals), sequences and series, probability, statistics, " +
                "number theory, matrices, vectors, word problems and unit conversions. " +
                "If a picture is given, first read the problem exactly as written (printed or handwritten); " +
                "if there are several problems, solve each one in order and number them. " +
                "Write short, clear numbered steps, then a last line that starts with the label for 'Final answer' " +
                "(translated into the reply language) followed by the result. Double-check every calculation. " +
                "Write maths in plain text using Unicode symbols such as \u221A \u00B2 \u00B3 \u00D7 \u00F7 \u03C0 \u222B \u2211 \u2264 \u2265 \u2260 \u221E and fractions like 3/4. " +
                "Never use LaTeX, markdown tables, or asterisks. " +
                "Reply in the same language as the user's text. If there is no text, reply in the language of the writing " +
                "in the picture, otherwise in " + language + "."

        val userText = buildString {
            if (topic.isNotBlank() && !topic.equals("auto", ignoreCase = true)) {
                append("Topic: ").append(topic).append('\n')
            }
            if (problem.isNotBlank()) append("Problem: ").append(problem)
            else append("Solve the problem shown in the picture.")
        }

        val parts = JSONArray().put(JSONObject().put("text", userText))
        if (jpeg != null && jpeg.isNotEmpty()) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            put("generationConfig", JSONObject().put("maxOutputTokens", 8192))
        }

        val reply = GeminiRest.generate(apiKey, body, budgetMs = 42_000L, strong = true)
        val json = reply.json
        if (json == null) {
            val friendly = when (reply.error) {
                "NO_KEY" -> "No Gemini API key is saved. Add it in Settings."
                "KEY" -> "Google rejected the Gemini API key. Check it in Settings."
                else -> "The solver could not be reached. Check the internet and try again."
            }
            return Solution("", friendly, reply.detail)
        }
        val text = GeminiRest.extractText(json)
        if (text.isEmpty()) {
            return Solution("", "The solver returned no answer. Try a clearer picture or retype the problem.",
                "finishReason=" + GeminiRest.finishReason(json))
        }
        return Solution(text, null, null)
    }

    fun encodeJpeg(source: Bitmap): ByteArray {
        var bmp = source
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }
}
