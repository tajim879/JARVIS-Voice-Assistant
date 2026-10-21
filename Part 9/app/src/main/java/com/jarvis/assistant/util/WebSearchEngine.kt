package com.jarvis.assistant.util

import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live web research using Gemini with Google Search grounding.
 * Uses the same Gemini API key the user already saved in Settings.
 */
object WebSearchEngine {

    private const val TAG = "WebSearchEngine"
    /**
     * Tried in order. gemini-2.5-flash is retired (Oct 2026), so we use the moving
     * "-latest" alias first and fall back to pinned newer models if an id disappears (HTTP 404).
     */
    private val MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-3.1-flash-lite")
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    data class Source(val title: String, val uri: String)

    data class SearchResult(
        val answer: String,
        val sources: List<Source>,
        val error: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(21, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, detailed: Boolean): SearchResult = withContext(Dispatchers.IO) {
        val apiKey = try { JarvisApp.instance.preferences.apiKey } catch (e: Exception) { "" }
        if (apiKey.isBlank()) {
            return@withContext SearchResult("", emptyList(), "No Gemini API key is saved. Ask the user to add it in Settings.")
        }

        val instruction = if (detailed) {
            "You are a research assistant. Search the web and write a thorough but well organised report: " +
                "short intro, key facts with dates and numbers, differing viewpoints if any, and a one-line conclusion. " +
                "Reply in the same language as the question. Do not paste URLs in the text."
        } else {
            "You are a fast web assistant. Search the web and answer in at most 5 short sentences or bullets with the most " +
                "important and most recent facts (include dates and numbers when relevant). " +
                "Reply in the same language as the question. Do not paste URLs in the text."
        }

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", query)))
                )
            )
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put(
                "generationConfig",
                JSONObject().put("temperature", 0.3).put("maxOutputTokens", if (detailed) 2048 else 800)
            )
        }

        val payload = body.toString().toRequestBody("application/json".toMediaType())
        var lastError = "Search failed."
        for (model in MODELS) {
            val request = Request.Builder()
                .url(BASE_URL + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(payload)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return@withContext parse(text)
                    }
                    Log.w(TAG, model + " -> HTTP " + response.code + ": " + text.take(200))
                    when (response.code) {
                        404 -> lastError = "Search model is unavailable right now."   // try the next model
                        400, 403 -> return@withContext SearchResult(
                            "", emptyList(),
                            "The Gemini API key was rejected. Ask the user to check it in Settings."
                        )
                        429 -> return@withContext SearchResult(
                            "", emptyList(),
                            "Search quota reached. Tell the user to try again in a minute."
                        )
                        else -> lastError = "Search failed (HTTP " + response.code + ")."
                    }
                    Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search error on " + model + ": " + e.message)
                return@withContext SearchResult(
                    "", emptyList(),
                    "Could not reach the web. Check the internet connection."
                )
            }
        }
        SearchResult("", emptyList(), lastError)
    }

    private fun parse(raw: String): SearchResult {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
        val first = candidates?.optJSONObject(0)
            ?: return SearchResult("", emptyList(), "The search returned no answer.")

        val parts = first.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (t.isNotBlank()) sb.append(t)
            }
        }
        val answer = sb.toString().trim()
        if (answer.isEmpty()) return SearchResult("", emptyList(), "The search returned no answer.")

        val sources = ArrayList<Source>()
        val seen = HashSet<String>()
        val chunks = first.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val uri = web.optString("uri", "")
                if (uri.isBlank() || !seen.add(uri)) continue
                val title = web.optString("title", "").ifBlank { uri }
                sources.add(Source(title, uri))
                if (sources.size >= 6) break
            }
        }
        return SearchResult(answer, sources)
    }
}
