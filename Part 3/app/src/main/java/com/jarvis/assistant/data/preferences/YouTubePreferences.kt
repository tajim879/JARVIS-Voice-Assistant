package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class YouTubePreferences(context: Context) {

    companion object {
        private const val TAG = "YouTubePreferences"
        private const val ENCRYPTED_FILE = "jarvis_youtube_secure_prefs"
        private const val FALLBACK_FILE = "jarvis_youtube_prefs"
        private const val KEY_YOUTUBE_API_KEY = "key_youtube_api_key"
        private const val KEY_YOUTUBE_ENABLED = "key_youtube_enabled"
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences initialization failed, falling back to standard prefs: ${e.message}")
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    var youtubeApiKey: String
        get() = prefs.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_API_KEY, value.trim()).apply()

    var isYouTubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()
}
