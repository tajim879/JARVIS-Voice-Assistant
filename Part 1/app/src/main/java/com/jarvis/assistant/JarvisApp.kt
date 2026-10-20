package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.repository.ChatRepository

class JarvisApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
