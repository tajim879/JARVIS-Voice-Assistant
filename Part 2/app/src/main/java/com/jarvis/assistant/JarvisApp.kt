package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.repository.ChatRepository

import com.jarvis.assistant.audio.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
        VoskModelManager.init(this, CoroutineScope(Dispatchers.Default))
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
