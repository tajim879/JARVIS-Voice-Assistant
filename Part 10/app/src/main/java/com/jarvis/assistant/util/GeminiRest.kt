package com.jarvis.assistant.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Small shared helper for Gemini "generateContent" REST calls (web search, math solver).
 *
 * Model ids get retired from time to time, so instead of trusting one hard-coded id this helper
 *  1. tries a short list of ids / "-latest" aliases,
 *  2. then asks the API which flash models this key can really use and tries those,
 *  3. remembers the id that worked,
 * and it returns the real reason (HTTP code + Google's message) when everything fails.
 */
object GeminiRest {

    private const val TAG = "GeminiRest"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"

    class Reply(val json: JSONObject?, val error: String?, val detail: String?, val model: String?)

    private class Outcome(val kind: Int, val json: JSONObject?, val message: String)

    private const val OK = 0
    private const val RETRY = 1
    private const val KEY_PROBLEM = 2

    private val FAST_MODELS = listOf(
        "gemini-flash-lite-latest", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"
    )
    private val STRONG_MODELS = listOf(
        "gemini-flash-latest", "gemini-3.8-flash", "gemini-flash-lite-latest", "gemini-3.5-flash-lite"
    )

    @Volatile private var discovered: List<String> = emptyList()
    @Volatile private var lastGoodFast: String? = null
    @Volatile private var lastGoodStrong: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun generate(apiKeyRaw: String, body: JSONObject, budgetMs: Long, strong: Boolean): Reply =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyRaw.trim()
            if (apiKey.isEmpty()) {
                return@withContext Reply(null, "NO_KEY", "No Gemini API key is saved in Settings.", null)
            }
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            val payload: RequestBody = body.toString().toRequestBody("application/json".toMediaType())
            val notes = ArrayList<String>()

            val first = if (strong) lastGoodStrong else lastGoodFast
            val queue = ArrayDeque<String>()
            if (first != null) queue.addLast(first)
            (if (strong) STRONG_MODELS else FAST_MODELS).forEach { queue.addLast(it) }
            discovered.forEach { queue.addLast(it) }

            val tried = HashSet<String>()
            var discoveryDone = discovered.isNotEmpty()
            while (queue.isNotEmpty()) {
                val model = queue.removeFirst()
                if (!tried.add(model)) continue
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining < 3000L) {
                    notes.add("out of time before trying " + model)
                    break
                }
                val attemptMs = minOf(remaining, if (strong) 32_000L else 15_000L)
                val outcome = callOnce(model, apiKey, payload, attemptMs)
                if (outcome.kind == OK) {
                    if (strong) lastGoodStrong = model else lastGoodFast = model
                    return@withContext Reply(outcome.json, null, null, model)
                }
                if (outcome.kind == KEY_PROBLEM) {
                    return@withContext Reply(null, "KEY", outcome.message, model)
                }
                notes.add(model + " -> " + outcome.message)
                if (queue.isEmpty() && !discoveryDone) {
                    discoveryDone = true
                    val found = discoverModels(apiKey)
                    discovered = found
                    found.forEach { queue.addLast(it) }
                }
            }
            Reply(null, "FAILED", notes.joinToString("; ").take(600), null)
        }

    private fun callOnce(model: String, apiKey: String, payload: RequestBody, timeoutMs: Long): Outcome {
        val request = Request.Builder()
            .url(BASE + "models/" + model + ":generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload)
            .build()
        val callClient = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        return try {
            callClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        Outcome(OK, JSONObject(text), "")
                    } catch (e: Exception) {
                        Outcome(RETRY, null, "unreadable response")
                    }
                } else {
                    val msg = errorMessage(text)
                    Log.w(TAG, model + " HTTP " + response.code + ": " + msg)
                    when (response.code) {
                        401, 403 -> Outcome(KEY_PROBLEM, null, "HTTP " + response.code + ": " + msg)
                        400 -> if (msg.contains("API key", ignoreCase = true)) {
                            Outcome(KEY_PROBLEM, null, "HTTP 400: " + msg)
                        } else {
                            Outcome(RETRY, null, "HTTP 400: " + msg)
                        }
                        else -> Outcome(RETRY, null, "HTTP " + response.code + ": " + msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, model + " failed: " + e.javaClass.simpleName + " " + e.message)
            Outcome(RETRY, null, e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
        }
    }

    private fun errorMessage(raw: String): String {
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message", "").orEmpty().ifBlank { raw.take(160) }.take(180)
        } catch (e: Exception) {
            raw.take(160)
        }
    }

    private fun discoverModels(apiKey: String): List<String> {
        return try {
            val request = Request.Builder()
                .url(BASE + "models?pageSize=200")
                .header("x-goog-api-key", apiKey)
                .build()
            client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val models = JSONObject(response.body?.string().orEmpty()).optJSONArray("models")
                    ?: return emptyList()
                val names = ArrayList<String>()
                for (i in 0 until models.length()) {
                    val o = models.getJSONObject(i)
                    val name = o.optString("name", "").removePrefix("models/")
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") canGenerate = true
                        }
                    }
                    val lower = name.lowercase()
                    val blocked = listOf("image", "tts", "audio", "live", "embed", "robotics", "computer", "preview-")
                    if (canGenerate && lower.contains("flash") && blocked.none { lower.contains(it) }) {
                        names.add(name)
                    }
                }
                names.sortedDescending().take(4)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Joins every normal text part of the first candidate (thinking parts are skipped). */
    fun extractText(json: JSONObject): String {
        val first = json.optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val t = part.optString("text", "")
            if (t.isNotBlank()) sb.append(t)
        }
        return sb.toString().trim()
    }

    fun finishReason(json: JSONObject): String =
        json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason", "").orEmpty()
}
