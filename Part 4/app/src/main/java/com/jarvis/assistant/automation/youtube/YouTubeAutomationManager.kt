package com.jarvis.assistant.automation.youtube

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.google.gson.JsonParser
import com.jarvis.assistant.data.preferences.YouTubePreferences
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class YouTubeAutomationManager(
    private val context: Context,
    private val preferences: YouTubePreferences
) {
    companion object {
        private const val TAG = "YouTubeManager"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    suspend fun playVideo(query: String): String = withContext(Dispatchers.IO) {
        val playResult = YouTubeController.searchAndPlay(context, query, preferences.youtubeApiKey)
        playResult.message ?: (if (playResult.success) "Playing on YouTube." else "Failed to play on YouTube.")
    }

    suspend fun searchYouTube(query: String): String = withContext(Dispatchers.IO) {
        val searchResult = YouTubeController.searchYouTube(context, query)
        searchResult.message ?: "Opening YouTube search."
    }

    fun scrapeFirstVideoId(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+1; PREF=tz=UTC")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                val queryWords = query.split(Regex("\\s+"))
                    .map { it.lowercase(Locale.ROOT) }
                    .filter { it.length > 1 && !listOf("song", "video", "gana", "track", "music", "play", "part").contains(it) }

                // Extract videoId and title from videoRenderer JSON objects
                val pattern = Regex(""""videoRenderer"\s*:\s*\{"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})".*?"title"\s*:\s*\{"runs"\s*:\s*\[\{"text"\s*:\s*"([^"]+)"""")
                val matches = pattern.findAll(html).take(12).toList()

                var bestVid: String? = null
                var bestScore = -1

                for (m in matches) {
                    val vid = m.groupValues[1]
                    val title = m.groupValues[2].lowercase(Locale.ROOT)

                    var score = queryWords.count { title.contains(it) }
                    if (title.contains("1 hour") || title.contains("jukebox") || title.contains("playlist")) {
                        score -= 1
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestVid = vid
                    }
                }

                // If we found a title matching at least one keyword, return it
                if (bestScore > 0 && bestVid != null) {
                    Log.i(TAG, "Smart scraper found best match vid=$bestVid with score=$bestScore")
                    return bestVid
                }

                // If no specific keyword had highest score or query words were varied,
                // return the #1 ranked videoRenderer result directly so the video ALWAYS plays
                if (matches.isNotEmpty()) {
                    val fallbackVid = matches[0].groupValues[1]
                    Log.i(TAG, "Smart scraper using top ranked search result vid=$fallbackVid")
                    return fallbackVid
                }

                // Generic videoRenderer fallback
                val genericPattern = Regex(""""videoRenderer"\s*:\s*\{[^}]*"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
                val genericMatch = genericPattern.find(html)
                if (genericMatch != null) {
                    val genericVid = genericMatch.groupValues[1]
                    Log.i(TAG, "Smart scraper matched generic videoRenderer vid=$genericVid")
                    return genericVid
                }

                Log.w(TAG, "No videoRenderer found for '$query'")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping YouTube: ${e.message}")
            null
        }
    }

    private fun searchFirstVideoId(query: String, apiKey: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=$encoded&key=$apiKey"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "YouTube API call failed: code=${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JsonParser.parseString(body).asJsonObject
                val items = json.getAsJsonArray("items")
                if (items != null && items.size() > 0) {
                    val firstItem = items.get(0).asJsonObject
                    val idObj = firstItem.getAsJsonObject("id")
                    if (idObj != null && idObj.has("videoId")) {
                        return idObj.get("videoId").asString
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception searching YouTube: ${e.message}", e)
            null
        }
    }

    private fun launchVideo(videoId: String) {
        val candidatePackages = listOf(YOUTUBE_PACKAGE, "app.revanced.android.youtube")
        for (pkg in candidatePackages) {
            try {
                val watchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    setPackage(pkg)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, watchIntent)) {
                    return
                }
            } catch (_: Exception) {
                try {
                    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        setPackage(pkg)
                    }
                    if (ActivityLauncherHelper.startActivitySafely(context, appIntent)) {
                        return
                    }
                } catch (_: Exception) {}
            }
        }

        // Browser fallback
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, webIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch video fallback: ${e.message}")
        }
    }

    private fun launchSearchFallback(query: String) {
        // Arm accessibility service to click the first search result video
        com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.triggerYouTubeAutoPlay()

        val candidatePackages = listOf(YOUTUBE_PACKAGE, "app.revanced.android.youtube")
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (pkg in candidatePackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, intent)) {
                    return
                }
            } catch (_: Exception) {}
        }

        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, webIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch search fallback: ${e.message}")
        }
    }

    // -------------------------------------------------------------
    // Channel & Player UI Actions (Subscribe / Fullscreen)
    // -------------------------------------------------------------

    fun subscribeToChannel(): String {
        com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.triggerYouTubeSubscribe()
        return "Subscribing to this channel."
    }

    fun toggleFullscreen(): String {
        com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.triggerYouTubeFullscreen()
        return "Switching to full screen."
    }

    // -------------------------------------------------------------
    // Media Playback Controls
    // -------------------------------------------------------------

    fun pausePlayback(): String {
        YouTubeController.sendMediaKey(context, "pause")
        return "Pausing playback."
    }

    fun resumePlayback(): String {
        YouTubeController.sendMediaKey(context, "play")
        return "Resuming playback."
    }

    fun nextMedia(): String {
        YouTubeController.sendMediaKey(context, "next")
        return "Skipping to next."
    }

    fun previousMedia(): String {
        YouTubeController.sendMediaKey(context, "previous")
        return "Going to previous."
    }

    suspend fun validateApiKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return@withContext Pair(false, "API Key is empty")
        }

        try {
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=test&key=$key"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Pair(true, "Key is valid and active!")
                } else {
                    val code = response.code
                    Pair(false, "Validation failed with HTTP $code. Check key & quota.")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Validation error: ${e.message}")
        }
    }
}
