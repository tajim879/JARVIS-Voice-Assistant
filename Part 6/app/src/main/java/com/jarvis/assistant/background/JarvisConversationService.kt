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
            val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
            captureManager.onStoppedListener = {
                activeInstance?.demoteFromMediaProjection()
            }
            captureManager.startCapture(projection, metrics) { jpegBytes ->
                val instance = activeInstance ?: return@startCapture
                if (instance.canSendVideoFrame()) {
                    instance.liveWebSocket?.sendMediaChunk(jpegBytes)
                }
            }
        }

        fun sendImmediateVideoFrame(jpegBytes: ByteArray) {
            val instance = activeInstance ?: return
            val ws = instance.liveWebSocket ?: return
            if (!ws.isSessionReady() || jpegBytes.isEmpty()) return
            // Block during THINKING — sending realtimeInput while server is generating causes barge-in
            if (_conversationState.value == ConversationState.THINKING) return
            // Block during SPEAKING — sending realtimeInput while audio streams causes barge-in
            if (_conversationState.value == ConversationState.SPEAKING) return
            if (instance.audioPlayer?.isPlaying() == true) return
            if (System.currentTimeMillis() - instance.lastPlaybackEndTime < 1500L) return
            ws.sendMediaChunk(jpegBytes)
        }

        fun stopScreenCapture() {
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().stopCapture()
            activeInstance?.demoteFromMediaProjection()
        }

        /** True while a foreground instance of this service exists in any mode. */
        fun isServiceRunning(): Boolean = activeInstance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences by lazy { (applicationContext as JarvisApp).preferences }

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
    private val currentUtteranceAudioStream = java.io.ByteArrayOutputStream()
    @Volatile
    private var isCurrentTurnOwnerVoiceVerified = false
    @Volatile
    private var lastOwnerVerifiedTimestamp = 0L
    private val preSpeechRingBuffer = java.util.ArrayDeque<ByteArray>(12)
    private val preConnectAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    // Autonomous Multi-Step Chaining Engine
    @Volatile
    private var isAutonomousTaskRunning = false
    @Volatile
    private var autonomousTaskGoal = ""
    @Volatile
    private var autonomousStepCount = 0
    private var autonomousLoopJob: Job? = null
    private val MAX_AUTONOMOUS_STEPS = 25

    // Pending tool call queue: tool calls are queued when owner voice verification hasn't completed yet
    private data class PendingToolCall(val callId: String, val name: String, val args: JSONObject)
    private val pendingToolCalls = java.util.concurrent.ConcurrentLinkedQueue<PendingToolCall>()

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

        // Guard against post-playback settling and acoustic echo:
        if (System.currentTimeMillis() - lastPlaybackEndTime < 1500L) return false

        // 2. CRITICAL: Never send video frames while assistant is THINKING!
        // Sending realtimeInput while Gemini is preparing a response causes Gemini to abort turn or send "interrupted: true".
        if (_conversationState.value == ConversationState.THINKING) return false

        // 3. Never send video frames while user voice is actively streaming
        if (hasSpeechInCurrentUtterance || consecutiveVoicedFrames > 0) return false

        // 4. WebSocket must be connected and setup completed
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
    private var headsetAudioRouter: com.jarvis.assistant.audio.HeadsetAudioRouter? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
        initTts()
        headsetAudioRouter = com.jarvis.assistant.audio.HeadsetAudioRouter(applicationContext)
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

    private fun generateCurrentSystemPrompt(): String {
        val app = application as? JarvisApp ?: return ""
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val memoryContext = app.memoryManager.getSystemPromptContext()
            val favSong = app.memoryManager.resolveSongQuery("my favorite song")
            com.jarvis.assistant.util.PromptGenerator.generateSystemPrompt(
                personality = preferences.personality,
                userName = preferences.userName,
                memoryContext = memoryContext,
                favoriteSong = favSong,
                userBirthday = preferences.userBirthday,
                isTorchOn = app.phoneControlManager.isTorchOn,
                language = preferences.language
            )
        }
    }

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
        val now = System.currentTimeMillis()
        lastUserSpeechDetectedTime = now
        lastOwnerVerifiedTimestamp = now
        isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
        pendingToolCalls.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        startInactivityTimer()
        _conversationState.value = ConversationState.IDLE
        updateNotification()
        headsetAudioRouter?.start()

        audioPlayer = AudioPlayer(
            context = this,
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
            val systemPrompt = generateCurrentSystemPrompt()

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
                                // Flush any speech chunks captured during the connection handshake
                                serviceScope.launch(Dispatchers.Default) {
                                    while (!preConnectAudioQueue.isEmpty()) {
                                        val chunk = preConnectAudioQueue.poll() ?: break
                                        liveWebSocket?.sendAudioChunk(chunk)
                                    }
                                }
                                // Reset autonomous task state on fresh connection
                                isAutonomousTaskRunning = false
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
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
                            // If owner verification is still pending (not yet determined), allow audio through.
                            // The verification system now runs EARLY during speech (~300ms in), so by the time
                            // Gemini sends response audio, verification should already be complete.
                            // Only suppress if we have POSITIVELY identified a non-owner (verification ran and failed).
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
                            if (isStopCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit stop/cancel in user speech: '$userText'. Aborting autonomous loop.")
                                isAutonomousTaskRunning = false
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            }
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
                            // Kill autonomous loop entirely on any interruption
                            isAutonomousTaskRunning = false
                            autonomousStepCount = 0
                            autonomousLoopJob?.cancel()
                            autonomousLoopJob = null
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
                            synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
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

                            // Owner voice gating: if owner-only mode is active and speaker was not verified, ignore turn completely
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
                                val nonOwnerUtterance = currentTurnUserText.toString().trim()
                                Log.w("JarvisService", "Turn completed by NON-OWNER: Suppressing response and logging ignored.")
                                val app = application as? JarvisApp
                                val loggedQuery = if (nonOwnerUtterance.isNotEmpty()) nonOwnerUtterance else "(Unrecognized voice)"
                                app?.chatRepository?.addTurn(
                                    userText = "[Non-Owner Voice]: $loggedQuery",
                                    jarvisText = "[IGNORED] Voice not recognized as owner"
                                )
                                audioPlayer?.flush()
                                currentTurnAssistantText.clear()
                                currentTurnUserText.clear()
                                _liveAssistantResponse.value = ""
                                _liveUserTranscript.value = ""
                                processPendingToolCalls(verified = false)
                                return
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

                            // Reset verification flag for the next conversational turn
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                                // Keep verification active if within the owner session lease window.
                                // This prevents the owner from being re-challenged every single turn.
                                val leaseStillActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
                                if (!leaseStillActive) {
                                    isCurrentTurnOwnerVoiceVerified = false
                                }
                            }
                            pendingToolCalls.clear()

                            // Reset autonomous task state on turn completion so state returns cleanly to IDLE
                            if (isAutonomousTaskRunning) {
                                Log.i("JarvisService", "Autonomous task step completed. Resetting task running state.")
                                isAutonomousTaskRunning = false
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            }
                        }

                        override fun onError(message: String) {
                            serviceScope.launch { _events.emit(message) }
                        }

                        override fun onToolCall(callId: String, name: String, args: JSONObject) {
                            // Owner voice gating: if verification hasn't completed yet, QUEUE the tool call
                            // instead of executing it. The silence detection handler will process or reject queued
                            // calls once it has enough audio to reliably verify the speaker.
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
                                Log.w("JarvisService", "QUEUED tool call '$name' (callId=$callId): Owner verification pending, will execute after silence detection confirms speaker.")
                                pendingToolCalls.add(PendingToolCall(callId, name, args))
                                return
                            }
                            handleToolCallExecution(callId, name, args)
                        }
                    }
                ).apply {
                    promptProvider = { generateCurrentSystemPrompt() }
                    connect(serviceScope)
                }
            }
        }

        audioRecorder = AudioRecorder { chunk, amplitude, frequencyData ->
            if (_isSessionOn.value) {
                val now = System.currentTimeMillis()

                // Acoustic Echo Decay Protection (AEDP):
                // For 200ms after speaker audio stops, discard mic chunks so room reverberation
                // does not bounce into Gemini Live.
                if (now - lastPlaybackEndTime < 200L) {
                    consecutiveVoicedFrames = 0
                    return@AudioRecorder
                }

                // Assistant is speaking / AudioTrack is playing:
                // CRITICAL: NEVER stream microphone chunks to Gemini Live while the assistant is producing sound!
                // Phone loudspeakers easily reach 0.35f-0.60f at the microphone. Streaming speaker sound back to
                // Gemini Live causes server-side barge-in detection ("interrupted": true), abruptly cutting
                // off the assistant mid-sentence!
                val isAssistantProducingSound = (_conversationState.value == ConversationState.SPEAKING) ||
                        (audioPlayer != null && audioPlayer!!.isPlaying())
                if (isAssistantProducingSound) {
                    val hasHeadset = headsetAudioRouter?.isHeadsetConnected() == true
                    // When on loudspeaker, the phone mic sits right next to the speaker and picks up speech at 0.35f-0.60f.
                    // To prevent self-interruption, require a loud, deliberate voice (0.72f) sustained for >16 frames (320ms).
                    // With a headset/earbuds, the mic is isolated from the speaker, so normal sensitivity (0.42f, 8 frames) is safe.
                    val bargeInAmpThreshold = if (hasHeadset) 0.42f else 0.72f
                    val bargeInMinFrames = if (hasHeadset) 8 else 16

                    if (amplitude > bargeInAmpThreshold) {
                        consecutiveVoicedFrames++
                        if (consecutiveVoicedFrames >= bargeInMinFrames) {
                            Log.i("JarvisService", "Voice barge-in: user spoke over assistant (amp=$amplitude, headset=$hasHeadset)")
                            audioPlayer?.flush()
                            autonomousLoopJob?.cancel()
                            autonomousLoopJob = null
                            _conversationState.value = ConversationState.LISTENING
                            lastUserSpeechDetectedTime = now
                            hasSpeechInCurrentUtterance = true
                            hasSignalledUtteranceEnd = false
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 4
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                currentUtteranceAudioStream.write(chunk)
                            }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                        }
                    } else {
                        consecutiveVoicedFrames = 0
                    }
                    return@AudioRecorder
                }

                // Assistant is not speaking: stream audio chunk to Gemini Live (or buffer if WebSocket is still connecting)
                if (_conversationState.value != ConversationState.THINKING) {
                    if (liveWebSocket?.isSessionReady() == true) {
                        while (!preConnectAudioQueue.isEmpty()) {
                            val queued = preConnectAudioQueue.poll() ?: break
                            liveWebSocket?.sendAudioChunk(queued)
                        }
                        liveWebSocket?.sendAudioChunk(chunk)
                    } else {
                        // Connection still warming up: buffer up to 60 chunks (~1.2s of audio) so early speech is never lost
                        if (preConnectAudioQueue.size < 60) {
                            preConnectAudioQueue.offer(chunk.copyOf())
                        }
                    }
                }

                // Track ambient noise floor adaptively when quiet
                if (amplitude < 0.10f) {
                    ambientNoiseFloor = (ambientNoiseFloor * 0.97f + amplitude * 0.03f).coerceIn(0.015f, 0.10f)
                }

                // Maintain pre-speech buffer of recent chunks (for smooth onset capture)
                synchronized(preSpeechRingBuffer) {
                    if (preSpeechRingBuffer.size >= 10) {
                        preSpeechRingBuffer.pollFirst()
                    }
                    preSpeechRingBuffer.addLast(chunk.copyOf())
                }

                // Dynamic voice threshold: responsive to normal conversational volume (0.035f)
                val voiceThreshold = maxOf(0.035f, ambientNoiseFloor * 1.5f)

                if (amplitude > voiceThreshold) {
                    consecutiveVoicedFrames++
                    lastUserSpeechDetectedTime = now
                    _audioLevel.value = amplitude
                    _audioFrequency.value = frequencyData

                    // Declare speech start after 3 consecutive voiced frames (~60ms)
                    if (consecutiveVoicedFrames >= 3) {
                        if (!hasSpeechInCurrentUtterance) {
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 0
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                synchronized(preSpeechRingBuffer) {
                                    for (preChunk in preSpeechRingBuffer) {
                                        currentUtteranceAudioStream.write(preChunk)
                                    }
                                }
                            }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                        }
                        hasSpeechInCurrentUtterance = true
                        hasSignalledUtteranceEnd = false
                        totalVoicedFramesInCurrentUtterance++
                        // EARLY VOICE VERIFICATION: Verify owner as soon as we have enough voiced audio (300ms)
                        // This ensures verification completes BEFORE Gemini starts sending response audio
                        if (!isCurrentTurnOwnerVoiceVerified && preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && totalVoicedFramesInCurrentUtterance == 15) {
                            val audioSnapshot = synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.toByteArray() }
                            if (audioSnapshot.size >= 16000 * 2 * 0.25) {
                                serviceScope.launch(Dispatchers.Default) {
                                    val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioSnapshot, preferences)
                                    if (match.isMatch || match.distance < match.threshold * 1.35f) {
                                        lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                                        isCurrentTurnOwnerVoiceVerified = true
                                        Log.i("JarvisService", "EARLY voice verification PASSED: Owner confirmed during speech (dist=${match.distance}, sim=${match.similarityPercent}%)")
                                        processPendingToolCalls(verified = true)
                                    } else if (match.hasExtractedVector) {
                                        Log.w("JarvisService", "EARLY voice verification FAILED: Non-owner detected (dist=${match.distance}, thr=${match.threshold})")
                                        // Don't reject yet - let silence handler do final check with more audio
                                    }
                                }
                            }
                        }
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                        if (_conversationState.value != ConversationState.LISTENING) {
                            _conversationState.value = ConversationState.LISTENING
                        }
                    }
                } else {
                    consecutiveVoicedFrames = 0
                    val silentDuration = now - lastUserSpeechDetectedTime

                    if (hasSpeechInCurrentUtterance && silentDuration < 420) {
                        // Keep buffering inter-word pauses so Vosk receives natural continuous audio
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                    }

                    if (_conversationState.value == ConversationState.LISTENING) {
                        _audioLevel.value = amplitude
                        _audioFrequency.value = frequencyData

                        // Conversational pause reached (~420ms for instant snappy turn-taking)
                        if (silentDuration > 420) {
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                                val isLeaseActive = (now - lastOwnerVerifiedTimestamp) < 120_000L
                                if (isLeaseActive) {
                                    // Ultra-low latency: Active owner lease immediately confirms turn with 0ms delay
                                    lastOwnerVerifiedTimestamp = now
                                    isCurrentTurnOwnerVoiceVerified = true
                                    processPendingToolCalls(verified = true)
                                } else if (!isCurrentTurnOwnerVoiceVerified) {
                                    val audioBytes = synchronized(currentUtteranceAudioStream) {
                                        currentUtteranceAudioStream.toByteArray()
                                    }
                                    if (audioBytes.size >= 16000 * 2 * 0.3) {
                                        val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioBytes, preferences)
                                        if (match.isMatch || match.distance < match.threshold * 1.35f) {
                                            lastOwnerVerifiedTimestamp = now
                                            isCurrentTurnOwnerVoiceVerified = true
                                            Log.i("JarvisService", "Speech verification passed: Owner confirmed (dist: ${match.distance}, sim: ${match.similarityPercent}%)")
                                            processPendingToolCalls(verified = true)
                                        } else {
                                            isCurrentTurnOwnerVoiceVerified = false
                                            audioPlayer?.flush()
                                            currentTurnAssistantText.clear()
                                            _liveAssistantResponse.value = ""
                                            Log.w("JarvisService", "Speech verification rejected: Non-owner (dist: ${match.distance}, thr: ${match.threshold})")
                                            processPendingToolCalls(verified = false)
                                        }
                                    } else {
                                        isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                                        if (!isCurrentTurnOwnerVoiceVerified) {
                                            processPendingToolCalls(verified = false)
                                        }
                                    }
                                }
                            }

                            if (totalVoicedFramesInCurrentUtterance >= 5 || currentTurnUserText.trim().isNotEmpty()) {
                                if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
                                    val nonOwnerQuery = currentTurnUserText.toString().trim()
                                    Log.w("JarvisService", "Non-owner speech ended: '$nonOwnerQuery'; IGNORING utterance, suppressing reply, and recording to history.")
                                    val app = application as? JarvisApp
                                    val loggedQuery = if (nonOwnerQuery.isNotEmpty()) nonOwnerQuery else "(Unrecognized voice)"
                                    app?.chatRepository?.addTurn(
                                        userText = "[Non-Owner Voice]: $loggedQuery",
                                        jarvisText = "[IGNORED] Voice not recognized as owner"
                                    )
                                    audioPlayer?.flush()
                                    currentTurnAssistantText.clear()
                                    currentTurnUserText.clear()
                                    _liveUserTranscript.value = ""
                                    _liveAssistantResponse.value = ""
                                    hasSpeechInCurrentUtterance = false
                                    hasSignalledUtteranceEnd = false
                                    consecutiveVoicedFrames = 0
                                    totalVoicedFramesInCurrentUtterance = 0
                                    synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                                    _conversationState.value = ConversationState.IDLE
                                    processPendingToolCalls(verified = false)
                                } else {
                                    if (!hasSignalledUtteranceEnd) {
                                        hasSignalledUtteranceEnd = true
                                        // If screen capture is active, send one fresh snapshot right with the question before audioStreamEnd
                                        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
                                            val snapshot = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().captureSnapshotJpeg(60)
                                            if (snapshot != null && snapshot.isNotEmpty()) {
                                                liveWebSocket?.sendMediaChunk(snapshot)
                                            }
                                        }
                                        liveWebSocket?.sendAudioStreamEnd()
                                    }
                                    _conversationState.value = ConversationState.THINKING
                                    thinkingTimeoutJob?.cancel()
                                    thinkingTimeoutJob = serviceScope.launch {
                                        delay(15000)
                                        if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                            Log.i("JarvisService", "Thinking timeout safety; returning to IDLE")
                                            _conversationState.value = ConversationState.IDLE
                                        }
                                    }
                                }
                            } else {
                                // Short acoustic pop / ambient noise - return cleanly to IDLE
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

    /**
     * Executes a tool call from Gemini. Called both from the direct onToolCall path
     * (when owner is already verified) and from processPendingToolCalls (after verification completes).
     */
    private fun handleToolCallExecution(callId: String, name: String, args: JSONObject) {
        serviceScope.launch(Dispatchers.IO) {
            isExecutingTool = true
            try {
                val app = application as? JarvisApp ?: return@launch

                hasExecutedCommandInCurrentTurn = true
                lastCommandExecutionTime = System.currentTimeMillis()
                suppressAudioUntilTurnComplete = false

                val isAutomationTool = name in setOf(
                    "open_app", "open_website", "search_in_chrome",
                    "click_ui_element", "type_text", "scroll_screen", "press_key"
                )
                if (name == "task_completed") {
                    Log.i("JarvisService", "task_completed received: Autonomous task marked COMPLETE.")
                    isAutonomousTaskRunning = false
                    autonomousStepCount = 0
                    autonomousLoopJob?.cancel()
                    autonomousLoopJob = null
                } else if (isAutomationTool) {
                    if (!isAutonomousTaskRunning) {
                        isAutonomousTaskRunning = true
                        autonomousStepCount = 1
                        val userText = currentTurnUserText.toString().trim()
                        autonomousTaskGoal = if (userText.isNotEmpty()) userText else "Complete $name automation task"
                        Log.i("JarvisService", "Started autonomous task loop with goal: '$autonomousTaskGoal'")
                    } else {
                        autonomousStepCount++
                        Log.i("JarvisService", "Autonomous task advancing to step $autonomousStepCount")
                    }
                }

                Log.i("JarvisService", "Handling native toolCall: $name with args: $args")
                val result = JSONObject()

                when (name) {
                    "open_app" -> {
                        val appName = args.optString("app_name", "")
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null

                        // 1. ALWAYS try launching the installed native app FIRST using AppLauncher!
                        val launched = com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, appName, appNumber)
                        if (launched) {
                            Log.i("JarvisService", "open_app: launched native app '$appName'")
                            result.put("success", true)
                            result.put("message", "Opened $appName app.")
                            delay(600)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                        } else {
                            // 2. Fallback: Only if native app is NOT installed on phone, check if it's a web platform (e.g. Google Flow)
                            val directWebUrl = app.phoneControlManager.parseTargetUrl(appName)
                            if (directWebUrl != null) {
                                Log.i("JarvisService", "open_app: '$appName' not installed as native app, opening web platform: $directWebUrl")
                                val webLaunched = app.phoneControlManager.openUrlsInChromeTabs(listOf(directWebUrl))
                                result.put("success", webLaunched)
                                result.put("message", if (webLaunched) "App '$appName' is not installed. Opened $directWebUrl in Chrome." else "Failed to open $directWebUrl.")
                                if (webLaunched) {
                                    delay(1200)
                                    val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                    val summary = orchestrator.inspectScreen(waitForContent = true)
                                    if (summary.isNotBlank()) {
                                        result.put("current_screen_elements", summary)
                                    }
                                }
                            } else {
                                result.put("success", false)
                                result.put("message", "Could not find or open $appName.")
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
                            delay(1000)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
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
                            delay(1000)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                        } else {
                            val targetUrl = app.phoneControlManager.parseTargetUrl(query)
                            val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                            val ok = app.phoneControlManager.openUrlsInChromeTabs(listOf(searchUrl))
                            result.put("success", ok)
                            result.put("message", if (targetUrl != null) "Opened $targetUrl in Chrome." else "Searched \"$query\" in Chrome.")
                            delay(1200)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                            if (targetUrl == null) {
                                result.put("action_required", "CRITICAL NEXT STEP: You are on the Google Search results page for \"$query\". Look at the search result links in current_screen_elements and IMMEDIATELY call click_ui_element with the link name or element_id to open the website! Do NOT claim you refined or created the video until you enter the website and submit the generation!")
                            }
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
                                lower == "play" || lower == "video" || lower == "play video" ||
                                lower == "a video" || lower == "song" || lower == "play song" ||
                                lower == "a song" || lower == "music" ||
                                lower.contains(" chalao") || lower.contains(" bajao")
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

                if (isAutomationTool && name != "task_completed") {
                    result.put("autonomous_status", "EXECUTED")
                    result.put("step_number", autonomousStepCount)
                    result.put("system_directive", "STEP $autonomousStepCount EXECUTED: Inspect current_screen_elements. Take the next single concrete action if needed, or call task_completed if the goal is fully accomplished. Execute ONE step at a time.")
                }

                liveWebSocket?.sendToolResponse(callId, name, result)
            } finally {
                isExecutingTool = false
            }
        }
    }



    private fun isStopCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        val patterns = listOf(
            "stop", "ruk jao", "ruko", "cancel", "abort", "pause",
            "bas karo", "thahar", "hold on", "ruk ja", "band karo"
        )
        return patterns.any { clean.contains(it) }
    }

    /**
     * Processes all queued tool calls after speaker verification completes.
     * If [verified] is true, each queued tool call is executed via handleToolCallExecution.
     * If [verified] is false, each queued tool call gets an "access denied" error response.
     */
    private fun processPendingToolCalls(verified: Boolean) {
        var pending = pendingToolCalls.poll()
        while (pending != null) {
            val tc = pending
            if (verified) {
                Log.i("JarvisService", "EXECUTING queued tool call '${tc.name}' (callId=${tc.callId}): Owner verified.")
                handleToolCallExecution(tc.callId, tc.name, tc.args)
            } else {
                Log.w("JarvisService", "REJECTED queued tool call '${tc.name}' (callId=${tc.callId}): Speaker is NOT verified owner.")
                liveWebSocket?.sendToolResponse(tc.callId, tc.name, JSONObject().put("error", "Access denied: Voice does not match owner."))
            }
            pending = pendingToolCalls.poll()
        }
    }

    private fun checkAndExecuteCommand(userSpeech: String) {
        if (hasExecutedCommandInCurrentTurn) return
        val cleanSpeech = userSpeech.trim()
        if (cleanSpeech.isBlank() || cleanSpeech == lastHandledCommandText) return

        if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
            Log.w("JarvisService", "BLOCKED local command execution: Speaker is not verified owner! ('$cleanSpeech')")
            return
        }

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
        val app = application as? JarvisApp
        val isHardwareTorchOn = app?.phoneControlManager?.isTorchOn ?: false
        if ((clean.contains("turning on") || clean.contains("turned on") || clean.contains("torch on") || clean.contains("flash on") || ((clean.contains("already on") || clean.contains("already turned on")) && !isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(true)
            matchSource = "ASSISTANT_FLASH_ON"
        } else if ((clean.contains("turning off") || clean.contains("turned off") || clean.contains("torch off") || clean.contains("flash off") || ((clean.contains("already off") || clean.contains("already turned off")) && isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
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
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube) {
            audioPlayer?.flush()
        }
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
            val isOwnerCheckPassed = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile() || isCurrentTurnOwnerVoiceVerified
            if (isOwnerCheckPassed) {
                app.chatRepository.addTurn(
                    userText = query.ifEmpty { "Spoken user query" },
                    jarvisText = reply
                )
                app.memoryManager.addShortTermTurn(
                    userText = query.ifEmpty { "Spoken user query" },
                    jarvisText = reply
                )
            }
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
        val prefs = (application as? JarvisApp)?.preferences
        val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 30_000L
        isCurrentTurnOwnerVoiceVerified = prefs == null || !prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile() || isLeaseActive
        pendingToolCalls.clear()
        synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
    }

    private fun stopActiveConversation() {
        inactivityJob?.cancel()
        inactivityJob = null
        _isSessionOn.value = false
        finalizeTurn()


        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        preConnectAudioQueue.clear()
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
        headsetAudioRouter?.stop()
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
            val spkModel = VoskModelManager.speakerModel
            if (spkModel != null) {
                rec.setSpeakerModel(spkModel)
                Log.i("JarvisService", "Attached SpeakerModel to Vosk wake recognizer for owner voice gating")
            }
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
                if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                    val spkArray = json.optJSONArray("spk")
                    if (spkArray != null && spkArray.length() > 0) {
                        val currentVector = FloatArray(spkArray.length()) { i -> spkArray.getDouble(i).toFloat() }
                        val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerVector(currentVector, preferences)
                        if (!match.isMatch) {
                            Log.w("JarvisService", "WAKE REJECTED: Heard '$text', but speaker distance (${match.distance}) > threshold (${match.threshold}). Non-owner ignored!")
                            return
                        } else {
                            lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                            isCurrentTurnOwnerVoiceVerified = true
                            Log.i("JarvisService", "WAKE VERIFIED: Owner voice confirmed for '$text'! (Distance: ${match.distance}, Similarity: ${match.similarityPercent}%)")
                        }
                    } else {
                        Log.w("JarvisService", "WAKE REJECTED: No speaker vector found in hypothesis for '$text' while owner voice is enabled. Non-owner ignored!")
                        return
                    }
                }
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
        if (audioManager.mode == AudioManager.MODE_IN_CALL) return true
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (telephonyManager != null && telephonyManager.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                return true
            }
        } catch (_: Exception) {}
        return false
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
