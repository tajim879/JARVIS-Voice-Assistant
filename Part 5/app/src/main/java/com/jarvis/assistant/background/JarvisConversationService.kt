package com.jarvis.assistant.background

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.VoskModelManager
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import com.jarvis.assistant.audio.AudioPlayer
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.network.GeminiLiveWebSocket
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.PromptGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Owns the *entire* Jarvis conversation lifecycle: the microphone, the audio
 * player, the Gemini Live WebSocket, the 120-second inactivity timer, and the
 * wake-word standby listener.
 *
 * This intentionally does NOT live inside the Activity/ViewModel. When the
 * user removes the app from Recents (or the screen turns off), Android
 * destroys the Activity and its ViewModel - if the live session lived there,
 * the conversation would die with it. Because everything here is owned by a
 * foreground service instead, the mic stays on and Jarvis keeps talking
 * normally even with the app closed. The Activity/ViewModel only *observes*
 * this service's state through the companion StateFlows below; they never
 * own the audio pipeline.
 */
class JarvisConversationService : Service() {

    companion object {
        const val ACTION_START_CONVERSATION = "com.jarvis.assistant.START_CONVERSATION"
        const val ACTION_STOP_ALL = "com.jarvis.assistant.STOP_ALL"
        const val ACTION_ENTER_STANDBY = "com.jarvis.assistant.ENTER_STANDBY"

        private const val CHANNEL_ID = "jarvis_conversation"
        private const val NOTIFICATION_ID = 41
        private const val INACTIVITY_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes before standby
        private const val STANDBY_COOLDOWN_MS = 1500L

        @Volatile private var activeInstance: JarvisConversationService? = null

        private val _isSessionOn = MutableStateFlow(false)
        val isSessionOn: StateFlow<Boolean> = _isSessionOn.asStateFlow()

        private val _isStandby = MutableStateFlow(false)
        val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

        private val _conversationState = MutableStateFlow(ConversationState.IDLE)
        val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

        private val _connectionStatus = MutableStateFlow("READY")
        val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private val _audioFrequency = MutableStateFlow(com.jarvis.assistant.audio.AudioFrequencyData())
        val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = _audioFrequency.asStateFlow()

        private val _liveUserTranscript = MutableStateFlow("")
        val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

        private val _liveAssistantResponse = MutableStateFlow("")
        val liveAssistantResponse: StateFlow<String> = _liveAssistantResponse.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val events: SharedFlow<String> = _events.asSharedFlow()

        /** True once a wake word has just brought the conversation back, for a one-off UI toast. */
        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

        fun setMicMuted(muted: Boolean) {
            activeInstance?.audioRecorder?.setMuted(muted)
        }

        fun interruptPlayback() {
            activeInstance?.let { service ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    Log.i("JarvisService", "Manual interruption: stopping audio playback")
                    service.audioPlayer?.flush()
                    _conversationState.value = ConversationState.LISTENING
                }
            }
        }

        val isScreenSharing: StateFlow<Boolean> = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing

        fun elevateToMediaProjection() {
            activeInstance?.elevateToMediaProjection()
        }

        fun demoteFromMediaProjection() {
            activeInstance?.demoteFromMediaProjection()
        }

        fun startScreenCapture(projection: android.media.projection.MediaProjection, metrics: android.util.DisplayMetrics) {
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().startCapture(projection, metrics) { jpegBytes ->
                val instance = activeInstance ?: return@startCapture
                if (instance.canSendVideoFrame()) {
                    instance.liveWebSocket?.sendMediaChunk(jpegBytes)
                }
            }
        }

        fun sendImmediateVideoFrame(jpegBytes: ByteArray) {
            val instance = activeInstance ?: return
            if (instance.canSendVideoFrame()) {
                instance.liveWebSocket?.sendMediaChunk(jpegBytes)
            }
        }

        fun stopScreenCapture() {
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().stopCapture()
            activeInstance?.demoteFromMediaProjection()
        }

        /** True while a foreground instance of this service exists in any mode. */
        fun isServiceRunning(): Boolean = activeInstance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Active-conversation components ---
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveWebSocket: GeminiLiveWebSocket? = null
    private var inactivityJob: Job? = null
    private val currentTurnAssistantText = StringBuilder()
    private val currentTurnUserText = StringBuilder()
    private var lastUserSpeechDetectedTime = 0L
    private var lastPlaybackEndTime = 0L
    private var hasSpeechInCurrentUtterance = false
    private var hasSignalledUtteranceEnd = false
    private var consecutiveVoicedFrames = 0
    private var totalVoicedFramesInCurrentUtterance = 0
    private var ambientNoiseFloor = 0.03f
    private var thinkingTimeoutJob: Job? = null
    private var isPendingBackgroundTransition = false
    @Volatile
    private var suppressAudioUntilTurnComplete = false
    @Volatile
    private var isExecutingTool = false

    /**
     * Determines whether a video frame can be sent to Gemini Live.
     * Video frames are streamed continuously during listening, thinking, and autonomous navigation
     * so Gemini can visually inspect the screen in real-time.
     * Video frames are paused ONLY while the assistant is speaking or playing audio to prevent client barge-in.
     */
    fun canSendVideoFrame(): Boolean {
        // 1. Never send video frames while assistant is speaking or hardware AudioTrack is playing audio.
        // Sending client realtimeInput while the server is streaming audio causes server-side barge-in / truncation.
        if (_conversationState.value == ConversationState.SPEAKING) return false
        if (audioPlayer?.isPlaying() == true) return false

        // 2. WebSocket must be connected and setup completed
        val ws = liveWebSocket ?: return false
        if (!ws.isSessionReady()) return false

        return true
    }

    // --- Standby / wake-word components (Pure Vosk) ---
    private var voskSpeechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var standbyActivatedTime = 0L
    @Volatile
    private var isPendingWakeGreeting = false

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d("JarvisService", "TTS onStart: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d("JarvisService", "TTS onDone: $utteranceId")
                            handleTtsDone(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e("JarvisService", "TTS onError: $utteranceId")
                            handleTtsDone(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e("JarvisService", "TTS onError: $utteranceId code: $errorCode")
                            handleTtsDone(utteranceId)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to init TTS: ${e.message}")
        }
    }

    private fun handleTtsDone(utteranceId: String?) {
        // Robotic system announcements disabled; all speech is handled via natural Gemini Live neural audio.
    }

    private fun ensureMicrophoneForegroundService() {
        val notification = buildNotification()
        var combinedTypes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            combinedTypes = combinedTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, combinedTypes)
                Log.d("JarvisService", "startForeground elevated to types=$combinedTypes")
            } catch (e: Exception) {
                Log.w("JarvisService", "startForeground with combined types failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (_: Exception) {}
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun elevateToMediaProjection() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service elevated to include MEDIA_PROJECTION")
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to elevate to MEDIA_PROJECTION: ${e.message}")
            }
        }
    }

    fun demoteFromMediaProjection() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service demoted from MEDIA_PROJECTION")
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to demote from MEDIA_PROJECTION: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMicrophoneForegroundService()
        acquireWakeLock()

        when (intent?.action) {
            ACTION_START_CONVERSATION -> {
                com.jarvis.assistant.audio.JarvisSoundEffects.playWakeSound()
                beginActiveConversation()
            }
            ACTION_ENTER_STANDBY -> enterStandby(announce = false)
            ACTION_STOP_ALL -> {
                fullStop()
                return START_NOT_STICKY
            }
            else -> {
                // System restarted service or restarted after recents removal
                if (!_isSessionOn.value && !_isStandby.value) {
                    enterStandby(announce = false)
                }
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Active conversation
    // ---------------------------------------------------------------------

    private fun beginActiveConversation(wakeTriggered: Boolean = false) {
        if (_isSessionOn.value) return
        exitStandbyListening()
        isPendingWakeGreeting = wakeTriggered

        val app = application as JarvisApp
        val preferences = app.preferences
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            serviceScope.launch { _events.emit("Please configure your Gemini API Key in Settings first.") }
            fullStop()
            return
        }

        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            serviceScope.launch { _events.emit("Please allow all required permissions in Settings to use Jarvis.") }
            fullStop()
            return
        }

        _isSessionOn.value = true
        _isStandby.value = false
        lastUserSpeechDetectedTime = System.currentTimeMillis()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        startInactivityTimer()
        _conversationState.value = ConversationState.IDLE
        updateNotification()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    _conversationState.value = ConversationState.SPEAKING
                } else {
                    lastPlaybackEndTime = System.currentTimeMillis()
                    finalizeTurn()
                    if (_isSessionOn.value) {
                        _conversationState.value = ConversationState.IDLE
                    }
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    _audioLevel.value = amp
                    _audioFrequency.value = com.jarvis.assistant.audio.AudioFrequencyData(
                        amplitude = amp,
                        pitchHz = 180f,
                        bass = (amp * 0.7f).coerceIn(0f, 1f),
                        mid = (amp * 0.9f).coerceIn(0f, 1f),
                        high = (amp * 0.4f).coerceIn(0f, 1f)
                    )
                }
            }
        ).apply { start() }

        serviceScope.launch(Dispatchers.IO) {
            val memoryContext = app.memoryManager.getSystemPromptContext()
            val favSong = app.memoryManager.resolveSongQuery("my favorite song")
            val systemPrompt = PromptGenerator.generateSystemPrompt(
                personality = preferences.personality,
                userName = preferences.userName,
                memoryContext = memoryContext,
                favoriteSong = favSong,
                userBirthday = preferences.userBirthday
            )

            withContext(Dispatchers.Main) {
                if (!_isSessionOn.value) return@withContext
                liveWebSocket = GeminiLiveWebSocket(
                    apiKey = apiKey,
                    model = preferences.aiModel,
                    voiceName = preferences.voice,
                    systemPrompt = systemPrompt,
                    listener = object : GeminiLiveWebSocket.Listener {
                        override fun onConnectionStateChanged(status: String) {
                            _connectionStatus.value = status
                            if (status == "LIVE") {
                                if (_conversationState.value != ConversationState.SPEAKING && _conversationState.value != ConversationState.THINKING) {
                                    _conversationState.value = ConversationState.IDLE
                                }
                                if (isPendingWakeGreeting) {
                                    isPendingWakeGreeting = false
                                    if (!hasSpeechInCurrentUtterance) {
                                        liveWebSocket?.sendClientContent("Hey Jarvis")
                                    }
                                }
                            }
                        }

                        override fun onAudioDataReceived(pcmData: ByteArray) {
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            if (suppressAudioUntilTurnComplete) return
                            _conversationState.value = ConversationState.SPEAKING
                            audioPlayer?.enqueueAudio(pcmData)
                        }

                        override fun onAssistantTextReceived(textChunk: String) {
                            if (textChunk.isEmpty()) return
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            synchronized(currentTurnAssistantText) {
                                val chunkToAppend = if (currentTurnAssistantText.isNotEmpty() && currentTurnAssistantText.last() == ' ') {
                                    textChunk.trimStart()
                                } else {
                                    textChunk
                                }
                                if (chunkToAppend.isNotEmpty()) {
                                    if (currentTurnAssistantText.isNotEmpty()) {
                                        val lastChar = currentTurnAssistantText.last()
                                        val firstChar = chunkToAppend.first()
                                        if (!Character.isWhitespace(lastChar) && !Character.isWhitespace(firstChar)) {
                                            if (Character.isLetterOrDigit(firstChar) || firstChar == '(' || firstChar == '[') {
                                                currentTurnAssistantText.append(' ')
                                            }
                                        }
                                    }
                                    currentTurnAssistantText.append(chunkToAppend)
                                }
                            }
                            _liveAssistantResponse.value = currentTurnAssistantText.toString().trim()
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            Log.d("JarvisService", "Assistant text so far: '$assistantText'")
                        }

                        override fun onUserTextReceived(textChunk: String) {
                            lastUserSpeechDetectedTime = System.currentTimeMillis()
                            val trimmed = textChunk.trim()
                            if (trimmed.isNotEmpty()) {
                                if (currentTurnUserText.isNotEmpty() && !currentTurnUserText.endsWith(" ")) {
                                    currentTurnUserText.append(" ")
                                }
                                currentTurnUserText.append(trimmed)
                                _liveUserTranscript.value = currentTurnUserText.toString().trim()
                                if (_conversationState.value == ConversationState.IDLE) {
                                    _conversationState.value = ConversationState.LISTENING
                                }
                            }
                            val userText = currentTurnUserText.toString().lowercase(Locale.ROOT)
                            if (isExplicitStandbyCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit background / standby command in user speech: '$userText'")
                                minimizeToHomeScreen()
                                enterStandby(announce = true)
                                return
                            }
                            app.memoryManager.learnPreferencesFromConversation(currentTurnUserText.toString())
                            // Do not run checkAndExecuteCommand on streaming chunks mid-speech!
                            // Gemini Live onToolCall is the authoritative neural intent engine.
                        }

                        override fun onInterrupted() {
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            suppressAudioUntilTurnComplete = false
                            audioPlayer?.flush()
                            currentTurnAssistantText.clear()
                            currentTurnUserText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            _conversationState.value = ConversationState.IDLE
                        }

                        override fun onTurnCompleted() {
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            audioPlayer?.notifyTurnComplete()
                            suppressAudioUntilTurnComplete = false
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                _conversationState.value = ConversationState.IDLE
                            }
                            val userUtterance = currentTurnUserText.toString().trim()
                            if (userUtterance.isNotEmpty()) {
                                app.memoryManager.learnPreferencesFromConversation(userUtterance)
                            }
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            val isConfused = assistantText.contains("not understand") ||
                                    assistantText.contains("didn't understand") ||
                                    assistantText.contains("don't understand") ||
                                    assistantText.contains("couldn't understand") ||
                                    assistantText.contains("sorry, i can't") ||
                                    assistantText.contains("i'm not sure")

                            // Only run fallback if Gemini Live did NOT call a tool AND did not express confusion
                            if (!hasExecutedCommandInCurrentTurn && !isConfused) {
                                checkAndExecuteCommand(userUtterance)
                                checkAssistantTextForCommands(currentTurnAssistantText.toString())
                            }
                        }

                        override fun onError(message: String) {
                            serviceScope.launch { _events.emit(message) }
                        }

                        override fun onToolCall(callId: String, name: String, args: JSONObject) {
                            serviceScope.launch(Dispatchers.IO) {
                                isExecutingTool = true
                                try {
                                    val app = application as? JarvisApp ?: return@launch
                                    hasExecutedCommandInCurrentTurn = true
                                    lastCommandExecutionTime = System.currentTimeMillis()
                                    suppressAudioUntilTurnComplete = false
                                    audioPlayer?.flush()

                                    Log.i("JarvisService", "Handling native toolCall: $name with args: $args")
                                    val result = JSONObject()

                                when (name) {
                                    "open_app" -> {
                                        val appName = args.optString("app_name", "")
                                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                                        val launched = com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, appName, appNumber)
                                        result.put("success", launched)
                                        result.put("message", if (launched) "Opened $appName." else "Could not find or open $appName.")
                                        if (launched) {
                                            delay(600)
                                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                            val summary = orchestrator.inspectScreen(waitForContent = true)
                                            if (summary.isNotBlank()) {
                                                result.put("current_screen_elements", summary)
                                            }
                                        }
                                    }

                                    "open_website" -> {
                                        val targetUrls = mutableListOf<String>()
                                        val jsonUrls = args.optJSONArray("urls")
                                        if (jsonUrls != null) {
                                            for (i in 0 until jsonUrls.length()) {
                                                val u = jsonUrls.getString(i).trim()
                                                if (u.isNotEmpty()) targetUrls.add(u)
                                            }
                                        }
                                        val query = args.optString("query", "").trim()
                                        if (query.isNotEmpty()) {
                                            val extracted = app.phoneControlManager.extractUrlsFromText(query)
                                            for (u in extracted) {
                                                if (!targetUrls.contains(u)) targetUrls.add(u)
                                            }
                                            if (targetUrls.isEmpty()) {
                                                val single = app.phoneControlManager.parseTargetUrl(query)
                                                if (single != null) targetUrls.add(single)
                                            }
                                        }
                                        if (targetUrls.isEmpty() && query.isNotBlank()) {
                                            targetUrls.add("https://www.google.com/search?q=${Uri.encode(query)}")
                                        }

                                        val ok = app.phoneControlManager.openUrlsInChromeTabs(targetUrls)
                                        result.put("success", ok)
                                        if (ok) {
                                            result.put("message", if (targetUrls.size > 1) "Opened ${targetUrls.size} websites in separate Chrome tabs: ${targetUrls.joinToString(", ")}." else "Opened ${targetUrls.firstOrNull() ?: "website"} in Chrome.")
                                        } else {
                                            result.put("message", "Could not open the requested websites.")
                                        }
                                    }

                                    "search_in_chrome" -> {
                                        val query = args.optString("query", "").trim()
                                        val extracted = app.phoneControlManager.extractUrlsFromText(query)
                                        if (extracted.isNotEmpty()) {
                                            val ok = app.phoneControlManager.openUrlsInChromeTabs(extracted)
                                            result.put("success", ok)
                                            result.put("message", if (extracted.size > 1) "Opened ${extracted.size} websites in separate Chrome tabs: ${extracted.joinToString(", ")}." else "Opened ${extracted.first()} in Chrome.")
                                        } else {
                                            val targetUrl = app.phoneControlManager.parseTargetUrl(query)
                                            val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                                            val ok = app.phoneControlManager.openUrlsInChromeTabs(listOf(searchUrl))
                                            result.put("success", ok)
                                            result.put("message", if (targetUrl != null) "Opened $targetUrl in Chrome." else "Searched \"$query\" in Chrome.")
                                        }
                                    }

                                    "search_and_play_youtube" -> {
                                        val query = args.optString("query", "").trim()
                                        audioPlayer?.flush()
                                        val apiKey = app.youTubePreferences.youtubeApiKey
                                        val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                                            "my favorite song"
                                        } else {
                                            query
                                        }
                                        val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                                        app.memoryManager.trackSongPlay(resolvedSong)
                                        val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                                        result.put("success", playResult.success)
                                        result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                                    }

                                    "search_youtube" -> {
                                        val query = args.optString("query", "").trim()
                                        val lower = query.lowercase()
                                        val isPlayIntent = lower.startsWith("play ") ||
                                                lower == "play" ||
                                                lower == "video" ||
                                                lower == "play video" ||
                                                lower == "a video" ||
                                                lower == "song" ||
                                                lower == "play song" ||
                                                lower == "a song" ||
                                                lower == "music" ||
                                                lower.contains(" chalao") ||
                                                lower.contains(" bajao")
                                        if (isPlayIntent) {
                                            Log.i("JarvisService", "STRICT RULE INTERCEPTION: search_youtube called with play intent ('$query') -> rerouting to search_and_play_youtube!")
                                            audioPlayer?.flush()
                                            val apiKey = app.youTubePreferences.youtubeApiKey
                                            val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("play video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("play song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                                                "my favorite song"
                                            } else {
                                                query.replace(Regex("(?i)^play\\s+"), "")
                                            }
                                            val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                                            app.memoryManager.trackSongPlay(resolvedSong)
                                            val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                                            result.put("success", playResult.success)
                                            result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                                        } else {
                                            val searchResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchYouTube(this@JarvisConversationService, query)
                                            result.put("success", searchResult.success)
                                            result.put("message", searchResult.message ?: "Opened YouTube search results.")
                                        }
                                    }

                                    "youtube_accessibility_action" -> {
                                        val action = args.optString("action", "").trim()
                                        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                                        if (a11y != null) {
                                            val actionOk = when (action) {
                                                "skip_ad" -> a11y.skipAd()
                                                "like" -> a11y.likeVideo()
                                                "subscribe" -> a11y.subscribeChannel()
                                                "open_channel" -> a11y.openChannel()
                                                "fullscreen" -> a11y.toggleFullscreen()
                                                "click_first" -> a11y.clickFirstVideoResult()
                                                else -> false
                                            }
                                            result.put("success", actionOk)
                                            result.put("message", if (actionOk) "Action '$action' executed." else "Could not perform action '$action'.")
                                        } else {
                                            result.put("success", false)
                                            result.put("message", "Accessibility service is not enabled. Please enable it in Settings.")
                                        }
                                    }

                                    "media_playback_control" -> {
                                        val action = args.optString("action", "").trim()
                                        val ok = com.jarvis.assistant.automation.youtube.YouTubeController.sendMediaKey(this@JarvisConversationService, action)
                                        result.put("success", ok)
                                        result.put("message", "Media action '$action' performed.")
                                    }

                                    "send_whatsapp_message" -> {
                                        val recipient = args.optString("recipient_name", "").trim()
                                        val message = args.optString("message", "").trim()
                                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                                        val confirmed = args.optBoolean("confirmed", true)
                                        audioPlayer?.flush()
                                        val waResult = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(this@JarvisConversationService, recipient, message, appNumber, confirmed)
                                        val (ok, msg) = when (waResult) {
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> Pair(true, "Sent WhatsApp message to ${waResult.contactName}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> Pair(true, "Please confirm sending WhatsApp message to ${waResult.contactName}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> Pair(false, "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> Pair(false, "Could not find contact ${waResult.name}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleContactsFound -> Pair(false, "Multiple contacts found for ${waResult.name}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MissingPermission -> Pair(false, "Contacts permission not granted.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> Pair(false, waResult.reason)
                                        }
                                        result.put("success", ok)
                                        result.put("message", msg)
                                    }

                                    "whatsapp_call" -> {
                                        val recipient = args.optString("recipient_name", "").trim()
                                        val callType = args.optString("call_type", "voice").trim()
                                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                                        val confirmed = args.optBoolean("confirmed", true)
                                        audioPlayer?.flush()
                                        val callResult = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(this@JarvisConversationService, recipient, callType, appNumber, confirmed)
                                        val (ok, msg) = when (callResult) {
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success -> Pair(true, "Calling ${callResult.contactName} on WhatsApp.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.RequiresConfirmation -> Pair(true, "Please confirm WhatsApp call to ${callResult.contactName}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleAppsFound -> Pair(false, "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.ContactNotFound -> Pair(false, "Could not find contact ${callResult.name}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleContactsFound -> Pair(false, "Multiple contacts found for ${callResult.name}.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MissingPermission -> Pair(false, "Contacts permission not granted.")
                                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Error -> Pair(false, callResult.reason)
                                        }
                                        result.put("success", ok)
                                        result.put("message", msg)
                                    }

                                    "call_contact" -> {
                                        val contact = args.optString("contact_name", "").trim()
                                        audioPlayer?.flush()
                                        val callResult = com.jarvis.assistant.util.ContactCaller.callContact(this@JarvisConversationService, contact)
                                        val (ok, msg) = when (callResult) {
                                            is com.jarvis.assistant.util.ContactCaller.CallResult.Success -> Pair(true, "Calling ${callResult.contact.name}.")
                                            is com.jarvis.assistant.util.ContactCaller.CallResult.NoMatch -> Pair(false, "Could not find contact $contact.")
                                            is com.jarvis.assistant.util.ContactCaller.CallResult.MultipleMatches -> Pair(false, "Multiple contacts found for $contact.")
                                            is com.jarvis.assistant.util.ContactCaller.CallResult.MissingPermission -> Pair(false, "Call phone permission not granted.")
                                            is com.jarvis.assistant.util.ContactCaller.CallResult.CallFailed -> Pair(false, "Could not place phone call.")
                                        }
                                        result.put("success", ok)
                                        result.put("message", msg)
                                    }

                                    "set_volume" -> {
                                        val action = args.optString("action", "").trim()
                                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                                        val msg = when {
                                            action == "set" && pct != null -> app.phoneControlManager.setVolumePercent(pct)
                                            action == "increase" -> app.phoneControlManager.adjustVolume(true)
                                            action == "decrease" -> app.phoneControlManager.adjustVolume(false)
                                            else -> "Volume unchanged."
                                        }
                                        result.put("success", true)
                                        result.put("message", msg)
                                    }

                                    "set_brightness" -> {
                                        val action = args.optString("action", "").trim()
                                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                                        val msg = when {
                                            action == "set" && pct != null -> app.phoneControlManager.setBrightnessPercent(pct)
                                            action == "increase" -> app.phoneControlManager.adjustBrightness(true)
                                            action == "decrease" -> app.phoneControlManager.adjustBrightness(false)
                                            else -> "Brightness unchanged."
                                        }
                                        result.put("success", true)
                                        result.put("message", msg)
                                    }

                                    "builtin_chrome_search" -> {
                                        val query = args.optString("query", "").trim()
                                        val searchExtracted = com.jarvis.assistant.util.BuiltInChromeEngine.searchAndExtract(query)
                                        result.put("success", searchExtracted.isNotBlank())
                                        result.put("result", searchExtracted)
                                    }

                                    "go_to_background" -> {
                                        minimizeToHomeScreen()
                                        enterStandby(announce = false)
                                        result.put("success", true)
                                        result.put("message", "Sent JARVIS to background standby mode.")
                                    }

                                    "set_flashlight" -> {
                                        val enabled = args.optBoolean("enabled", true)
                                        val msg = app.phoneControlManager.setFlashlight(enabled)
                                        result.put("success", true)
                                        result.put("message", msg)
                                    }

                                    "click_ui_element" -> {
                                        val targetName = args.optString("target_name", "")
                                        val elementId = if (args.has("element_id")) args.optInt("element_id") else null
                                        val isToggle = args.optBoolean("is_toggle", false)
                                        val desc = args.optString("description", "")
                                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                        val actionRes = orchestrator.handleClick(targetName, elementId, desc, isToggle)
                                        result.put("success", actionRes.success)
                                        result.put("message", actionRes.message)
                                        if (actionRes.currentStateSummary.isNotBlank()) {
                                            result.put("current_screen_elements", actionRes.currentStateSummary)
                                        }
                                        if (actionRes.requiresUserConfirmation) {
                                            result.put("requires_confirmation", true)
                                        }
                                    }

                                    "type_text" -> {
                                        val text = args.optString("text", "")
                                        val targetInput = args.optString("target_input_name", "")
                                        val submit = args.optBoolean("submit", false)
                                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                        val actionRes = orchestrator.handleType(text, targetInput.takeIf { it.isNotBlank() }, submit)
                                        result.put("success", actionRes.success)
                                        result.put("message", actionRes.message)
                                        if (actionRes.currentStateSummary.isNotBlank()) {
                                            result.put("current_screen_elements", actionRes.currentStateSummary)
                                        }
                                    }

                                    "scroll_screen" -> {
                                        val dir = args.optString("direction", "down")
                                        val dist = args.optString("distance", "small")
                                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                        val actionRes = orchestrator.handleScroll(dir, dist)
                                        result.put("success", actionRes.success)
                                        result.put("message", actionRes.message)
                                        if (actionRes.currentStateSummary.isNotBlank()) {
                                            result.put("current_screen_elements", actionRes.currentStateSummary)
                                        }
                                    }

                                    "press_key" -> {
                                        val key = args.optString("key", "back")
                                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                        val actionRes = orchestrator.handleKey(key)
                                        result.put("success", actionRes.success)
                                        result.put("message", actionRes.message)
                                    }

                                    "task_completed" -> {
                                        val summary = args.optString("summary", "Task completed.")
                                        Log.i("JarvisService", "Visual task completed: $summary")
                                        result.put("success", true)
                                        result.put("message", "Task completed: $summary")
                                    }

                                    "inspect_screen" -> {
                                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                        val summary = orchestrator.inspectScreen(waitForContent = true)
                                        result.put("success", true)
                                        result.put("screen_summary", summary)
                                        result.put("message", "Screen inspected successfully.")
                                    }

                                    else -> {
                                        Log.w("JarvisService", "Unknown toolCall name: $name")
                                        result.put("success", false)
                                        result.put("message", "Unknown tool: $name")
                                    }
                                }

                                    liveWebSocket?.sendToolResponse(callId, name, result)
                                } finally {
                                    isExecutingTool = false
                                }
                            }
                        }
                    }
                ).apply { connect(serviceScope) }
            }
        }

        audioRecorder = AudioRecorder { chunk, amplitude, frequencyData ->
            if (_isSessionOn.value) {
                val now = System.currentTimeMillis()

                // Acoustic Echo Decay Protection (AEDP):
                // For 400ms after speaker audio stops, discard mic chunks so room reverberation
                // and AGC surge do not bounce into Gemini Live and trigger false speech detection.
                if (now - lastPlaybackEndTime < 400L) {
                    consecutiveVoicedFrames = 0
                    return@AudioRecorder
                }

                // Assistant is speaking:
                // Do NOT abruptly flush AudioPlayer on normal speaker sound! Phone speakers easily reach
                // 0.2f-0.4f at the mic. Gemini Live itself has server-side neural barge-in detection and
                // sends "interrupted: true" when the user speaks over the assistant.
                // We only do local client-side emergency barge-in if the user speaks loudly (> 0.48f)
                // for 8+ consecutive frames (~160ms).
                if (_conversationState.value == ConversationState.SPEAKING) {
                    if (amplitude > 0.48f) {
                        consecutiveVoicedFrames++
                        if (consecutiveVoicedFrames >= 8) {
                            Log.i("JarvisService", "Emergency local barge-in: user spoke loudly over assistant")
                            audioPlayer?.flush()
                            _conversationState.value = ConversationState.LISTENING
                            liveWebSocket?.sendAudioChunk(chunk)
                            lastUserSpeechDetectedTime = now
                            hasSpeechInCurrentUtterance = true
                            hasSignalledUtteranceEnd = false
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 8
                        }
                    } else {
                        consecutiveVoicedFrames = 0
                    }
                    // During assistant speech, only stream chunks to Gemini Live if amplitude suggests active voice (> 0.25f)
                    // so we do not pump raw speaker loopback into the server.
                    if (amplitude > 0.25f) {
                        liveWebSocket?.sendAudioChunk(chunk)
                    }
                    return@AudioRecorder
                }

                // Assistant is not speaking: stream audio chunk to Gemini Live
                liveWebSocket?.sendAudioChunk(chunk)

                // Track ambient noise floor adaptively when quiet
                if (amplitude < 0.10f) {
                    ambientNoiseFloor = (ambientNoiseFloor * 0.97f + amplitude * 0.03f).coerceIn(0.015f, 0.10f)
                }

                // Dynamic voice threshold: must be above room noise and at least 0.14f
                val voiceThreshold = maxOf(0.14f, ambientNoiseFloor * 2.2f)

                if (amplitude > voiceThreshold) {
                    consecutiveVoicedFrames++
                    lastUserSpeechDetectedTime = now
                    _audioLevel.value = amplitude
                    _audioFrequency.value = frequencyData

                    // Require 6 consecutive voiced frames (~120ms) before declaring speech start
                    if (consecutiveVoicedFrames >= 6) {
                        if (!hasSpeechInCurrentUtterance) {
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 0
                        }
                        hasSpeechInCurrentUtterance = true
                        hasSignalledUtteranceEnd = false
                        totalVoicedFramesInCurrentUtterance++
                        if (_conversationState.value != ConversationState.LISTENING) {
                            _conversationState.value = ConversationState.LISTENING
                        }
                    }
                } else {
                    consecutiveVoicedFrames = 0
                    val silentDuration = now - lastUserSpeechDetectedTime

                    if (_conversationState.value == ConversationState.LISTENING) {
                        _audioLevel.value = amplitude
                        _audioFrequency.value = frequencyData

                        // After user was speaking, wait for conversational pause (~650ms)
                        if (silentDuration > 650) {
                            // STRICT RULE: ONLY enter THINKING if user text was ACTUALLY transcribed by Gemini Live!
                            if (currentTurnUserText.trim().isNotEmpty()) {
                                if (!hasSignalledUtteranceEnd) {
                                    hasSignalledUtteranceEnd = true
                                    liveWebSocket?.sendAudioStreamEnd()
                                }
                                _conversationState.value = ConversationState.THINKING
                                thinkingTimeoutJob?.cancel()
                                thinkingTimeoutJob = serviceScope.launch {
                                    delay(2500)
                                    if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                        Log.i("JarvisService", "Thinking timeout safety; returning to IDLE")
                                        _conversationState.value = ConversationState.IDLE
                                    }
                                }
                            } else {
                                // No words were transcribed (it was only ambient room noise or breathing)
                                // NEVER enter THINKING! Cleanly return to IDLE!
                                hasSpeechInCurrentUtterance = false
                                hasSignalledUtteranceEnd = false
                                _conversationState.value = ConversationState.IDLE
                            }
                        }
                    } else if (_conversationState.value == ConversationState.IDLE) {
                        _audioLevel.value = 0f
                    }
                }
            }
        }.apply {
            setMuted(preferences.isMicMuted)
            start(serviceScope)
        }
    }

    private var lastHandledCommandText = ""
    private var lastHandledAssistantCommand = ""
    @Volatile
    private var hasExecutedCommandInCurrentTurn = false
    @Volatile
    private var lastCommandExecutionTime = 0L

    // Holds a command that Jarvis heard but isn't confident enough about to run blindly
    // (e.g. a YouTube title that sounded too short/garbled). Cleared once the user
    // confirms or cancels it in a follow-up turn.
    @Volatile
    private var pendingGenericCommand: com.jarvis.assistant.automation.command.JarvisCommand? = null

    private fun checkAndExecuteCommand(userSpeech: String) {
        if (hasExecutedCommandInCurrentTurn) return
        val cleanSpeech = userSpeech.trim()
        if (cleanSpeech.isBlank() || cleanSpeech == lastHandledCommandText) return

        Log.d("JarvisService", "checkAndExecuteCommand: '$cleanSpeech'")

        val app = application as? JarvisApp ?: return
        val isAwaitingConfirm = app.whatsAppAssistant.hasPendingDraft() || pendingGenericCommand != null
        val isAwaitingDisambig = app.whatsAppAssistant.isAwaitingDisambiguation()

        val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(
            input = cleanSpeech,
            isAwaitingConfirmation = isAwaitingConfirm,
            isAwaitingDisambiguation = isAwaitingDisambig
        )

        val cmd = parsed.command

        // A generic pending confirmation (e.g. an unclear "play X" request) takes
        // priority over a WhatsApp draft confirmation if somehow both are pending.
        if (cmd is com.jarvis.assistant.automation.command.JarvisCommand.ConfirmAction && pendingGenericCommand != null) {
            lastHandledCommandText = cleanSpeech
            val genericCmd = pendingGenericCommand
            pendingGenericCommand = null
            if (cmd.confirmed && genericCmd != null) {
                Log.i("JarvisService", "User confirmed pending command: $genericCmd")
                executeDetectedCommand(genericCmd, cleanSpeech)
            } else {
                Log.i("JarvisService", "User cancelled pending command: $genericCmd")
                announce("Okay, I won't do that.")
            }
            return
        }

        if (cmd != null && parsed.confidence >= 0.90f) {
            // If the requested video/song is too vague or could easily be a mis-hearing
            // (e.g. Jarvis only caught one short/filler word), confirm with the user
            // instead of guessing and playing the wrong thing.
            if (cmd is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube &&
                com.jarvis.assistant.automation.command.CommandParser.isAmbiguousPlayQuery(cmd.query)
            ) {
                lastHandledCommandText = cleanSpeech
                pendingGenericCommand = cmd
                Log.i("JarvisService", "Ambiguous YouTube query '${cmd.query}', asking for confirmation before playing")
                announce("Did you want me to play \"${cmd.query}\" on YouTube? Say yes to confirm, or tell me the name again.")
                return
            }

            lastHandledCommandText = cleanSpeech
            Log.i("JarvisService", "USER TEXT → Detected command [${parsed.matchedPattern}] confidence=${parsed.confidence}: $cmd")
            executeDetectedCommand(cmd, cleanSpeech)
        } else {
            Log.d("JarvisService", "No command match from user text (confidence=${parsed.confidence})")
        }
    }

    /**
     * Speaks a short message locally via TTS and also surfaces it in the chat/event UI,
     * used for quick confirmations/cancellations that don't need a full Gemini round-trip.
     */
    private fun announce(message: String) {
        serviceScope.launch { _events.emit(message) }
    }

    /**
     * Dual-Path Execution Engine:
     * When user speech parsing doesn't trigger a command due to background noise or slight transcription variances,
     * Gemini Live's natural language comprehension reliably recognizes the user's intent and speaks the standardized
     * action phrase (e.g., "Opening vercel.com in browser", "Searching for techno gamers on google", "Playing Tadipaar on YouTube",
     * "Sending WhatsApp message to Rahul: hello", "Calling Mom").
     *
     * This handler parses those standardized phrases and executes them immediately.
     * Guarded by [hasExecutedCommandInCurrentTurn] to prevent duplicate execution in the same turn.
     */
    private fun checkAssistantTextForCommands(assistantText: String) {
        if (hasExecutedCommandInCurrentTurn || (System.currentTimeMillis() - lastCommandExecutionTime < 3500L)) return
        val rawClean = assistantText.trim()
        val clean = rawClean.lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?', ',', ' ')
        if (clean.isBlank() || clean == lastHandledAssistantCommand) return

        var command: com.jarvis.assistant.automation.command.JarvisCommand? = null
        var matchSource = ""

        // --- 1. Flashlight ---
        if ((clean.contains("turning on") || clean.contains("turned on") || clean.contains("torch on") || clean.contains("flash on")) && (clean.contains("flash") || clean.contains("torch"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(true)
            matchSource = "ASSISTANT_FLASH_ON"
        } else if ((clean.contains("turning off") || clean.contains("turned off") || clean.contains("torch off") || clean.contains("flash off")) && (clean.contains("flash") || clean.contains("torch"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(false)
            matchSource = "ASSISTANT_FLASH_OFF"
        }

        // --- 2. Brightness ---
        else if (clean.startsWith("setting brightness") || clean.startsWith("adjusting brightness") || clean.startsWith("increasing brightness") || clean.startsWith("decreasing brightness")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(level)
                matchSource = "ASSISTANT_BRIGHTNESS_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = 20)
                matchSource = "ASSISTANT_BRIGHTNESS_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = -20)
                matchSource = "ASSISTANT_BRIGHTNESS_DOWN"
            }
        }

        // --- 3. Volume ---
        else if (clean.startsWith("setting volume") || clean.startsWith("adjusting volume") || clean.startsWith("increasing volume") || clean.startsWith("decreasing volume") || clean.startsWith("muting")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(level)
                matchSource = "ASSISTANT_VOLUME_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = 1)
                matchSource = "ASSISTANT_VOLUME_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = -1)
                matchSource = "ASSISTANT_VOLUME_DOWN"
            } else if (clean.contains("mut")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0)
                matchSource = "ASSISTANT_VOLUME_MUTE"
            }
        }

        // --- 4. Web Search: "Searching for [topic] on Google", "Searching google for [topic]" ---
        else if (clean.startsWith("searching for ") || clean.startsWith("searching google for ")) {
            val searchMatch = Regex("^(?:searching\\s+for\\s+|searching\\s+google\\s+for\\s+)(.+?)(?:\\s+(?:on\\s+google|in\\s+browser))?$").find(clean)
            if (searchMatch != null) {
                val query = searchMatch.groupValues[1].trim()
                if (query.isNotBlank() && query.length > 1) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.SearchWeb(query)
                    matchSource = "ASSISTANT_WEB_SEARCH"
                }
            }
        }

        // --- 5. YouTube Play: "Playing [song] on YouTube", "Playing your favorite song, [song], on YouTube" ---
        else if (clean.startsWith("playing ")) {
            val ytMatch = Regex("^(?:playing\\s+(?:your\\s+favou?rite\\s+song,\\s+)?)(.+?)(?:\\s*,?\\s*on\\s+youtube)?$").find(clean)
            if (ytMatch != null) {
                val song = ytMatch.groupValues[1].trim().trimEnd(',', '!')
                if (song.isNotBlank()) {
                    val finalSong = if (song.equals("music", ignoreCase = true) || song.equals("video", ignoreCase = true) ||
                        song.equals("a video", ignoreCase = true) || song.equals("a song", ignoreCase = true)) {
                        (application as? JarvisApp)?.memoryManager?.resolveSongQuery("my favorite song") ?: "my favorite song"
                    } else {
                        song
                    }
                    command = com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube(finalSong)
                    matchSource = "ASSISTANT_YOUTUBE_PLAY"
                }
            }
        }

        // --- 5b. YouTube Subscribe: "Subscribing to this channel!" ---
        else if (clean.startsWith("subscribing")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.SubscribeChannel
            matchSource = "ASSISTANT_YOUTUBE_SUBSCRIBE"
        }

        // --- 5c. YouTube Fullscreen: "Switching to full screen!" ---
        else if (clean.contains("full screen") || clean.contains("fullscreen")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.ToggleFullscreen
            matchSource = "ASSISTANT_YOUTUBE_FULLSCREEN"
        }

        // --- 5d. WhatsApp Delete: "Deleting the last message for everyone!" / "Deleting the last message!" ---
        else if (clean.startsWith("deleting")) {
            val forEveryone = clean.contains("everyone")
            val contactMatch = Regex("(?:to|for|from)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:for|from)\\s+everyone)?$").find(clean)
            val contact = contactMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "everyone" && it != "me" }
            command = com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone)
            matchSource = "ASSISTANT_WHATSAPP_DELETE"
        }

        // --- 6. WhatsApp Message: "Sending WhatsApp message to [Contact]: [Message]" ---
        else if (clean.startsWith("sending whatsapp message to ") || clean.startsWith("sending message to ")) {
            val waMatch = Regex("^(?:sending\\s+whatsapp\\s+message\\s+to\\s+|sending\\s+message\\s+to\\s+)([a-zA-Z0-9\\s]+?):\\s*(.+)$", RegexOption.IGNORE_CASE).find(rawClean)
            if (waMatch != null) {
                val contact = waMatch.groupValues[1].trim()
                val message = waMatch.groupValues[2].trim()
                if (contact.isNotBlank() && message.isNotBlank()) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppMessage(contact, message)
                    matchSource = "ASSISTANT_WHATSAPP_SEND"
                }
            }
        }

        // --- 7. Website Navigation: "Opening [url] in browser", "Opening website [url]" ---
        else if (clean.startsWith("opening ") && (clean.contains("in browser") || clean.contains("in chrome") || clean.contains("website") || clean.contains(".com") || clean.contains(".org") || clean.contains(".dev") || clean.contains(".in") || clean.contains(".net") || clean.contains(".io"))) {
            val urlMatch = Regex("^(?:opening\\s+website\\s+|opening\\s+)(.+?)(?:\\s+in\\s+browser|\\s+in\\s+chrome|\\s+website)?$").find(clean)
            if (urlMatch != null) {
                val target = urlMatch.groupValues[1].trim()
                val normalizedUrl = com.jarvis.assistant.automation.phone.DomainNormalizer.normalizeToUrl(target)
                command = com.jarvis.assistant.automation.command.JarvisCommand.OpenWebUrls(listOf(normalizedUrl))
                matchSource = "ASSISTANT_OPEN_WEBSITE"
            }
        }

        // --- 8. Phone / WhatsApp Call: "Calling [Contact]", "Calling [Contact] on WhatsApp" ---
        else if (clean.startsWith("calling ")) {
            val callMatch = Regex("^calling\\s+([a-zA-Z0-9\\s+]+?)(?:\\s+on\\s+whatsapp)?$").find(clean)
            if (callMatch != null) {
                val target = callMatch.groupValues[1].trim()
                if (target.isNotBlank() && target.length >= 2 && !listOf("it", "that", "you", "someone").contains(target)) {
                    command = if (clean.contains("whatsapp")) {
                        com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppCall(target, video = false)
                    } else {
                        com.jarvis.assistant.automation.command.JarvisCommand.PhoneCall(target)
                    }
                    matchSource = "ASSISTANT_CALL"
                }
            }
        }

        // --- 9. App Launch: "Opening [App Name]" (fallback if not a website) ---
        else if (clean.startsWith("opening ")) {
            val appMatch = Regex("^opening\\s+([a-zA-Z0-9\\s]+?)(?:\\s+app)?$").find(clean)
            if (appMatch != null) {
                val app = appMatch.groupValues[1].trim()
                if (app.isNotBlank() && !listOf("website", "tab", "browser", "chrome").contains(app)) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.OpenApp(app)
                    matchSource = "ASSISTANT_OPEN_APP"
                }
            }
        }

        // --- 10. Background Transition: "Going to the background", "I am going to the background" ---
        else if (clean.contains("going to the background") || clean.contains("going in the background") || clean.contains("going to background")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground
            matchSource = "ASSISTANT_BACKGROUND"
        }

        if (command != null) {
            lastHandledAssistantCommand = clean
            Log.i("JarvisService", "ASSISTANT TEXT → Detected hardware command [$matchSource]: $command")
            executeDetectedCommand(command, "assistant: $clean")
        }
    }

    private fun executeDetectedCommand(command: com.jarvis.assistant.automation.command.JarvisCommand, source: String) {
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground) {
            minimizeToHomeScreen()
            enterStandby(announce = false)
            return
        }
        hasExecutedCommandInCurrentTurn = true
        lastCommandExecutionTime = System.currentTimeMillis()
        suppressAudioUntilTurnComplete = false
        audioPlayer?.flush()
        val app = application as? JarvisApp ?: return

        serviceScope.launch {
            try {
                val result = app.commandExecutor.execute(command)
                Log.i("JarvisService", "Command executed successfully: $result (source: $source)")
                app.memoryManager.addShortTermTurn(source, result)
            } catch (e: Exception) {
                Log.e("JarvisService", "Command execution FAILED: ${e.message}", e)
            }
        }
    }

    private fun finalizeTurn() {
        val rawReply = currentTurnAssistantText.toString().trim()
        val reply = rawReply.replace(Regex("\\s+"), " ")
        val rawQuery = currentTurnUserText.toString().trim()
        val query = rawQuery.replace(Regex("\\s+"), " ")
        val app = application as? JarvisApp

        if (query.isNotEmpty()) {
            app?.memoryManager?.learnPreferencesFromConversation(query)
        }

        if (reply.isNotEmpty() && app != null) {
            app.chatRepository.addTurn(
                userText = query.ifEmpty { "Spoken user query" },
                jarvisText = reply
            )
            app.memoryManager.addShortTermTurn(
                userText = query.ifEmpty { "Spoken user query" },
                jarvisText = reply
            )
        }
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        _liveUserTranscript.value = ""
        _liveAssistantResponse.value = ""
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        consecutiveVoicedFrames = 0
        totalVoicedFramesInCurrentUtterance = 0
        hasExecutedCommandInCurrentTurn = false
        lastHandledCommandText = ""
        lastHandledAssistantCommand = ""
    }

    private fun stopActiveConversation() {
        inactivityJob?.cancel()
        inactivityJob = null
        _isSessionOn.value = false
        finalizeTurn()


        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false

        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.flush()
        audioPlayer?.release()
        audioPlayer = null

        liveWebSocket?.disconnect()
        liveWebSocket = null

        _conversationState.value = ConversationState.IDLE
        _connectionStatus.value = "READY"
        _audioLevel.value = 0f
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            while (isActive && _isSessionOn.value) {
                delay(15_000)
                if (System.currentTimeMillis() - lastUserSpeechDetectedTime >= INACTIVITY_TIMEOUT_MS) {
                    enterStandby(announce = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Standby / wake-word listening (Pure Vosk - Continuous Streaming)
    // ---------------------------------------------------------------------

    private fun enterStandby(announce: Boolean = false) {
        if (_isStandby.value) return
        com.jarvis.assistant.audio.JarvisSoundEffects.playStandbySound()
        isPendingBackgroundTransition = false
        stopActiveConversation()
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        _isStandby.value = true
        standbyActivatedTime = System.currentTimeMillis()
        updateNotification()

        serviceScope.launch(Dispatchers.Main) {
            delay(500)
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        try {
            voskSpeechService?.stop()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error stopping Vosk SpeechService: ${e.message}")
        }
        voskSpeechService = null
    }

    private fun startWakeListening() {
        if (!_isStandby.value) return
        if (voskSpeechService != null) return

        val model = VoskModelManager.model
        if (model == null) {
            Log.i("JarvisService", "Vosk model still loading; triggering init and will start once ready")
            VoskModelManager.init(applicationContext, serviceScope)
            serviceScope.launch {
                VoskModelManager.isModelReady.collect { ready ->
                    if (ready && _isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
            return
        }

        try {
            standbyActivatedTime = System.currentTimeMillis()
            // Phrase grammar: allow single-word "jarvis", multi-syllable wake phrases, and [unk]
            val grammar = "[\"jarvis\", \"hey jarvis\", \"hi jarvis\", \"hello jarvis\", \"ok jarvis\", \"okay jarvis\", \"wake up jarvis\", \"jarvis jarvis\", \"[unk]\"]"
            val rec = Recognizer(model, 16000.0f, grammar)
            val service = SpeechService(rec, 16000.0f)
            service.startListening(voskListener)
            voskSpeechService = service
            Log.i("JarvisService", "Vosk wake-word listening active (strict multi-syllable phrase grammar)")
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to start Vosk SpeechService: ${e.message}", e)
            try {
                voskSpeechService?.stop()
            } catch (_: Exception) {}
            voskSpeechService = null
            serviceScope.launch { _events.emit("Mic error: ${e.message}") }
        }
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // NEVER trigger wake word on partial results.
            // Partial results in Kaldi are unstable, unpruned intermediate guesses
            // that cause spurious false-positive wakeups on ambient noise.
        }

        override fun onResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onError(exception: Exception?) {
            Log.e("JarvisService", "Vosk error: ${exception?.message}")
            if (_isStandby.value) {
                serviceScope.launch(Dispatchers.Main) {
                    delay(1500)
                    if (_isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
        }

        override fun onTimeout() {
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return
        if (isPhoneCallActive()) return

        // Standby cooldown: ignore any microphone input for the first 1.5 seconds after entering standby
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) {
            Log.d("JarvisService", "Ignored hypothesis during standby cooldown")
            return
        }

        try {
            val json = JSONObject(hypothesisJson)
            val text = if (json.has("text")) {
                json.getString("text")
            } else {
                ""
            }.trim().lowercase(Locale.ROOT)

            if (text.isEmpty() || text == "[unk]") return

            Log.i("JarvisService", "Vosk standby confirmed: '$text'")
            if (containsWakeWord(text)) {
                Log.i("JarvisService", "Vosk confirmed wake word: '$text'")
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e("JarvisService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    private fun containsWakeWord(phrase: String): Boolean {
        val words = phrase.lowercase(Locale.ROOT).trim()
        val validWakePhrases = listOf(
            "jarvis",
            "hey jarvis",
            "hi jarvis",
            "hello jarvis",
            "ok jarvis",
            "okay jarvis",
            "wake up jarvis",
            "jarvis jarvis"
        )
        return validWakePhrases.any { wakePhrase ->
            words == wakePhrase || words.startsWith("$wakePhrase ")
        }
    }

    private fun onWakeWordDetected() {
        if (!_isStandby.value) return
        vibrateBriefly()
        com.jarvis.assistant.audio.JarvisSoundEffects.playWakeSound()
        exitStandbyListening()
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        serviceScope.launch { _wakeEvents.emit(Unit) }
        beginActiveConversation(wakeTriggered = true)
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------
    // Lifecycle plumbing
    // ---------------------------------------------------------------------

    private fun fullStop() {
        stopActiveConversation()
        exitStandbyListening()
        _isStandby.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisConversation")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "$packageName:JarvisWifi"
            )?.also { it.acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        stopActiveConversation()
        exitStandbyListening()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        try {
            voskSpeechService?.stop()
        } catch (_: Exception) {}
        voskSpeechService = null
        releaseWakeLock()
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Command and Call Helpers
    // ---------------------------------------------------------------------

    private fun isExplicitStandbyCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        val patterns = listOf(
            "go to background", "go background", "go in background", "go in the background",
            "background me jao", "background mein jao", "send to background", "move to background",
            "minimize", "go home", "go to home", "go to home screen",
            "go to sleep", "sleep jarvis", "enter standby", "standby mode", "go to standby",
            "take a break", "so jao"
        )
        return patterns.any { clean.contains(it) }
    }

    private fun minimizeToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(this, homeIntent)
            Log.i("JarvisService", "Successfully navigated to phone home screen")
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to navigate to home screen: ${e.message}", e)
        }
    }

    private fun isPhoneCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.mode == AudioManager.MODE_IN_CALL ||
               audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("JarvisService", "onTaskRemoved: App removed from recents. Keeping service alive.")
        acquireWakeLock()
        // Do not invoke AlarmManager with PendingIntent.getService which throws background execution crashes on Android 8+.
        // As a persistent foreground service with stopWithTask="false", Jarvis stays alive in standby.
        if (!_isSessionOn.value && !_isStandby.value) {
            enterStandby(announce = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val (title, text) = when {
            _isSessionOn.value -> "Jarvis is listening" to "Conversation is active"
            _isStandby.value -> "Jarvis is in the background" to "Say \"Hey Jarvis\" to wake me up"
            else -> "Jarvis" to "Starting up\u2026"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", PendingIntent.getService(
                this, 0, Intent(this, JarvisConversationService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
    }
}
