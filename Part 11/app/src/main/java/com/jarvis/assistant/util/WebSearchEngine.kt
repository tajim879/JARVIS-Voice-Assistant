package com.jarvis.assistant.util

import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Live web research.
 *  1. Gemini with Google Search grounding (uses the Gemini API key saved in Settings).
 *  2. If that fails for any reason, a plain DuckDuckGo results list so the user still gets something.
 * The returned [SearchResult.detail] always carries the technical reason when something failed.
 */
object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    data class Source(val title: String, val uri: String)

    data class SearchResult(
        val answer: String,
        val sources: List<Source>,
        val error: String? = null,
        val detail: String? = null,
        /** True when the answer is a raw result list because Gemini search was unavailable. */
        val fallback: Boolean = false
    )

    private val ddgClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, detailed: Boolean): SearchResult {
        val apiKey = try { JarvisApp.instance.preferences.apiKey } catch (e: Exception) { "" }

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
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", query)))
            ))
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            // Thinking models count their thoughts inside this limit, so keep it generous.
            put("generationConfig", JSONObject().put("maxOutputTokens", if (detailed) 8192 else 4096))
        }

        val reply = GeminiRest.generate(apiKey, body, budgetMs = 34_000L, strong = detailed)
        if (reply.json != null) {
            val parsed = parse(reply.json)
            if (parsed.error == null) return parsed
            Log.w(TAG, "Gemini answered without text: " + parsed.error)
            return fallbackSearch(query, parsed.error + " (model " + reply.model + ")")
        }

        val friendly = when (reply.error) {
            "NO_KEY" -> "No Gemini API key is saved. Ask the user to add it in Settings."
            "KEY" -> "Google rejected the Gemini API key. Ask the user to check it in Settings."
            else -> "Web search is not reachable right now."
        }
        // A bad key will fail the fallback too only if it needs the key, so always try it.
        val fb = fallbackSearch(query, reply.detail ?: friendly)
        return if (fb.error == null) fb else fb.copy(error = friendly)
    }

    private fun parse(json: JSONObject): SearchResult {
        val first = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: return SearchResult("", emptyList(), "The search returned no answer.", "no candidates")
        val answer = GeminiRest.extractText(json)
        if (answer.isEmpty()) {
            return SearchResult(
                "", emptyList(), "The search returned no answer.",
                "empty text, finishReason=" + GeminiRest.finishReason(json)
            )
        }
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

    // ------------------------------------------------------------------ DuckDuckGo fallback

    private suspend fun fallbackSearch(query: String, whyGeminiFailed: String): SearchResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .header("Accept-Language", "en,bn;q=0.8")
                    .build()
                val html = ddgClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body?.string().orEmpty()
                }
                val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(html).toList()
                val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(html).toList()
                if (links.isEmpty()) {
                    return@withContext SearchResult("", emptyList(), "No results found.", whyGeminiFailed)
                }
                val sources = ArrayList<Source>()
                val sb = StringBuilder()
                for ((i, m) in links.withIndex()) {
                    if (sources.size >= 6) break
                    val title = clean(m.groupValues[2])
                    val link = decodeDdgLink(m.groupValues[1])
                    if (title.isEmpty() || link.isEmpty()) continue
                    val snippet = if (i < snippets.size) clean(snippets[i].groupValues[1]) else ""
                    sources.add(Source(title, link))
                    sb.append("\u2022 ").append(title)
                    if (snippet.isNotEmpty()) sb.append(" - ").append(snippet)
                    sb.append("\n\n")
                }
                if (sources.isEmpty()) {
                    SearchResult("", emptyList(), "No results found.", whyGeminiFailed)
                } else {
                    SearchResult(sb.toString().trim(), sources, null, whyGeminiFailed, fallback = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DuckDuckGo fallback failed: " + e.message)
                SearchResult("", emptyList(), "Could not reach the web.", whyGeminiFailed + " | fallback: " + e.javaClass.simpleName)
            }
        }

    private fun decodeDdgLink(raw: String): String {
        var link = raw.replace("&amp;", "&")
        val marker = "uddg="
        val idx = link.indexOf(marker)
        if (idx >= 0) {
            val end = link.indexOf('&', idx)
            val enc = if (end > idx) link.substring(idx + marker.length, end) else link.substring(idx + marker.length)
            link = try { URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { enc }
        }
        if (link.startsWith("//")) link = "https:" + link
        return if (link.startsWith("http://") || link.startsWith("https://")) link else ""
    }

    private fun clean(html: String): String {
        return html.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
