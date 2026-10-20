package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            return if (GeminiConstants.SUPPORTED_MODELS.contains(stored)) {
                stored
            } else {
                GeminiConstants.DEFAULT_MODEL
            }
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT) ?: GeminiConstants.PERSONALITY_ASSISTANT
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
