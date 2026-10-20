package com.jarvis.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
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
        private const val INACTIVITY_TIMEOUT_MS = 120_000L
        private const val BACKGROUND_ANNOUNCEMENT =
            "Sir, if you want me, call my wake-up words."

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

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val events: SharedFlow<String> = _events.asSharedFlow()

        /** True once a wake word has just brought the conversation back, for a one-off UI toast. */
        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

        fun setMicMuted(muted: Boolean) {
            activeInstance?.audioRecorder?.setMuted(muted)
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
    private var hasSpeechInCurrentUtterance = false
    private var hasSignalledUtteranceEnd = false

    // --- Standby / wake-word components (Pure Vosk) ---
    private var voskSpeechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        when (intent?.action) {
            ACTION_START_CONVERSATION -> beginActiveConversation()
            ACTION_ENTER_STANDBY -> enterStandby(announce = true)
            ACTION_STOP_ALL -> {
                fullStop()
                return START_NOT_STICKY
            }
            else -> {
                // System-restarted the service after it was killed (e.g. low
                // memory) with no explicit action. Nothing meaningful to
                // resume automatically; just keep the process quiet until the
                // user (or Recents removal) triggers a real action again.
                if (!_isSessionOn.value && !_isStandby.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Active conversation
    // ---------------------------------------------------------------------

    private fun beginActiveConversation() {
        if (_isSessionOn.value) return
        exitStandbyListening()

        val app = application as JarvisApp
        val preferences = app.preferences
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            serviceScope.launch { _events.emit("Please configure your Gemini API Key in Settings first.") }
            fullStop()
            return
        }

        _isSessionOn.value = true
        _isStandby.value = false
        lastUserSpeechDetectedTime = System.currentTimeMillis()
        startInactivityTimer()
        _conversationState.value = ConversationState.IDLE
        currentTurnAssistantText.clear()
        updateNotification()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    _conversationState.value = ConversationState.SPEAKING
                } else {
                    finalizeTurn()
                    if (_isSessionOn.value) {
                        _conversationState.value = ConversationState.IDLE
                    }
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    _audioLevel.value = amp
                }
            }
        ).apply { start() }

        val systemPrompt = PromptGenerator.generateSystemPrompt(
            personality = preferences.personality,
            userName = preferences.userName
        )

        liveWebSocket = GeminiLiveWebSocket(
            apiKey = apiKey,
            model = preferences.aiModel,
            voiceName = preferences.voice,
            systemPrompt = systemPrompt,
            listener = object : GeminiLiveWebSocket.Listener {
                override fun onConnectionStateChanged(status: String) {
                    _connectionStatus.value = status
                    if (status == "LIVE" && _conversationState.value == ConversationState.IDLE) {
                        _conversationState.value = ConversationState.LISTENING
                    }
                }

                override fun onAudioDataReceived(pcmData: ByteArray) {
                    _conversationState.value = ConversationState.SPEAKING
                    audioPlayer?.enqueueAudio(pcmData)
                }

                override fun onAssistantTextReceived(textChunk: String) {
                    currentTurnAssistantText.append(textChunk)
                    val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                    if (isAssistantBackgroundAnnouncement(assistantText)) {
                        Log.i("JarvisService", "Assistant acknowledged background transition: '$assistantText'")
                        enterStandby(announce = true)
                    }
                }

                override fun onUserTextReceived(textChunk: String) {
                    lastUserSpeechDetectedTime = System.currentTimeMillis()
                    currentTurnUserText.append(" ").append(textChunk)
                    val userText = currentTurnUserText.toString().lowercase(Locale.ROOT)
                    if (isBackgroundCommand(userText)) {
                        Log.i("JarvisService", "Detected background command in accumulated user speech: '$userText'")
                        enterStandby(announce = true)
                    }
                }

                override fun onInterrupted() {
                    audioPlayer?.flush()
                    currentTurnAssistantText.clear()
                    currentTurnUserText.clear()
                    _conversationState.value = ConversationState.LISTENING
                }

                override fun onTurnCompleted() {
                    // Handled when playback drains in AudioPlayer.
                }

                override fun onError(message: String) {
                    serviceScope.launch { _events.emit(message) }
                }
            }
        ).apply { connect(serviceScope) }

        audioRecorder = AudioRecorder { chunk, amplitude ->
            if (_isSessionOn.value) {
                liveWebSocket?.sendAudioChunk(chunk)

                if (amplitude > 0.15f) {
                    lastUserSpeechDetectedTime = System.currentTimeMillis()
                    if (!hasSpeechInCurrentUtterance) {
                        currentTurnUserText.clear()
                    }
                    hasSpeechInCurrentUtterance = true
                    hasSignalledUtteranceEnd = false
                    if (_conversationState.value != ConversationState.SPEAKING) {
                        _conversationState.value = ConversationState.LISTENING
                        _audioLevel.value = amplitude
                    }
                } else if (_conversationState.value == ConversationState.LISTENING) {
                    _audioLevel.value = amplitude
                    val silentDuration = System.currentTimeMillis() - lastUserSpeechDetectedTime
                    if (hasSpeechInCurrentUtterance && !hasSignalledUtteranceEnd && silentDuration > 600) {
                        hasSignalledUtteranceEnd = true
                        liveWebSocket?.sendAudioStreamEnd()
                    }
                    if (silentDuration > 900 && lastUserSpeechDetectedTime > 0) {
                        _conversationState.value = ConversationState.THINKING
                    }
                }
            }
        }.apply {
            setMuted(preferences.isMicMuted)
            start(serviceScope)
        }
    }

    private fun finalizeTurn() {
        val reply = currentTurnAssistantText.toString().trim()
        if (reply.isNotEmpty()) {
            (application as JarvisApp).chatRepository.addTurn(
                userText = currentTurnUserText.toString().trim().ifEmpty { "Spoken user query" },
                jarvisText = reply
            )
            currentTurnAssistantText.clear()
            currentTurnUserText.clear()
        }
    }

    private fun stopActiveConversation() {
        inactivityJob?.cancel()
        inactivityJob = null
        _isSessionOn.value = false
        finalizeTurn()

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
                    enterStandby(announce = true)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Standby / wake-word listening (Pure Vosk - Continuous Streaming)
    // ---------------------------------------------------------------------

    private fun enterStandby(announce: Boolean) {
        if (_isStandby.value) return
        stopActiveConversation()
        _isStandby.value = true
        updateNotification()
        if (announce) {
            announceThenListen()
        } else {
            startWakeListening()
        }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        unregisterAudioPlaybackMonitor()
        try {
            voskSpeechService?.stop()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error stopping Vosk SpeechService: ${e.message}")
        }
        voskSpeechService = null

        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun announceThenListen() {
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) {
                        if (_isStandby.value) startWakeListening()
                    }
                    override fun onDone(utteranceId: String?) {
                        if (_isStandby.value) startWakeListening()
                    }
                })
                tts?.speak(BACKGROUND_ANNOUNCEMENT, TextToSpeech.QUEUE_FLUSH, null, "background-announcement")
            } else {
                if (_isStandby.value) startWakeListening()
            }
        }
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

        serviceScope.launch(Dispatchers.Main) {
            // Give the audio hardware 350ms to release the microphone from Gemini Live
            delay(350)
            if (!_isStandby.value || voskSpeechService != null) return@launch

            try {
                // Focus grammar only on the wake words.
                // "wake up" is deliberately excluded so reels/songs/dialogue never falsely wake Jarvis.
                val grammar = "[\"hey jarvis\", \"hi jarvis\", \"hello jarvis\", \"ok jarvis\", \"jarvis\", \"[unk]\"]"
                val rec = Recognizer(model, 16000.0f, grammar)
                val service = SpeechService(rec, 16000.0f)
                service.startListening(voskListener)
                voskSpeechService = service

                registerAudioPlaybackMonitor()
                if (isSpeakerMediaOrCallActive()) {
                    service.setPause(true)
                    Log.i("JarvisService", "Vosk standby listening active but initially paused (speaker media/call active)")
                } else {
                    Log.i("JarvisService", "Vosk wake-word listening active (continuous mic, noise-filtered)")
                }
            } catch (e: Exception) {
                Log.e("JarvisService", "Failed to start Vosk SpeechService: ${e.message}", e)
                try {
                    voskSpeechService?.stop()
                } catch (_: Exception) {}
                voskSpeechService = null
                _events.emit("Mic error: ${e.message}")
            }
        }
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Deliberately ignore partial speculative results.
            // Only finalized speech segments in onResult are evaluated to
            // completely prevent keyboard typing or transient noise from triggering false wake-ups.
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
                    delay(2000)
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

        // Safety guard: if media is currently playing through phone speakers,
        // or a phone call is active, ignore the hypothesis to prevent false wake-ups from reels/music.
        if (isSpeakerMediaOrCallActive()) {
            Log.d("JarvisService", "Ignored Vosk hypothesis during speaker media/call playback")
            return
        }

        try {
            val json = JSONObject(hypothesisJson)
            if (!json.has("text")) return
            val text = json.getString("text").trim()
            if (text.isEmpty()) return

            Log.d("JarvisService", "Vosk standby recognized phrase: '$text'")
            if (containsWakeWord(text)) {
                Log.i("JarvisService", "Vosk confirmed wake word in phrase: '$text'")
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e("JarvisService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    /** Recognizes any of: "Hey Jarvis", "Hi Jarvis", "Hello Jarvis", "OK Jarvis", or "Jarvis". */
    private fun containsWakeWord(phrase: String): Boolean {
        val words = phrase.lowercase(Locale.ROOT)
        return Regex("\\b(hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis)\\b").containsMatchIn(words)
    }

    private fun onWakeWordDetected() {
        if (!_isStandby.value) return
        exitStandbyListening()
        serviceScope.launch { _wakeEvents.emit(Unit) }
        acknowledgeThenActivate()
    }

    private fun acknowledgeThenActivate() {
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) {
                        serviceScope.launch(Dispatchers.Main) { beginActiveConversation() }
                    }
                    override fun onDone(utteranceId: String?) {
                        serviceScope.launch(Dispatchers.Main) { beginActiveConversation() }
                    }
                })
                tts?.speak("Yes, sir?", TextToSpeech.QUEUE_FLUSH, null, "wake-ack")
            } else {
                beginActiveConversation()
            }
        }
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
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisConversation")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        stopActiveConversation()
        exitStandbyListening()
        unregisterAudioPlaybackMonitor()
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
    // Media Playback & Call Guard Helpers
    // ---------------------------------------------------------------------

    private fun isBackgroundCommand(text: String): Boolean {
        val backgroundPattern = Regex(
            "\\b(jarvis[,;.!?\\s]*)?(go|stay|move|enter|switch)\\s+(to|into|in\\s+to|in)?\\s*(the\\s+)?(background|standby)\\b|" +
            "\\bjarvis[,;.!?\\s]+(standby|sleep|background|go\\s+to\\s+sleep)\\b"
        )
        return backgroundPattern.containsMatchIn(text)
    }

    private fun isAssistantBackgroundAnnouncement(text: String): Boolean {
        return text.contains("going in the background") ||
               text.contains("going into the background") ||
               text.contains("going to the background") ||
               text.contains("going to background") ||
               text.contains("call my wake-up words") ||
               text.contains("call my wake up words")
    }

    private fun registerAudioPlaybackMonitor() {
        if (audioPlaybackCallback != null) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (!_isStandby.value) return
                val isSpeakerActive = isSpeakerMediaOrCallActive()
                try {
                    voskSpeechService?.setPause(isSpeakerActive)
                    if (isSpeakerActive) {
                        Log.d("JarvisService", "Vosk standby paused: media playing on phone speaker")
                    } else {
                        Log.d("JarvisService", "Vosk standby resumed: speaker audio stopped")
                    }
                } catch (e: Exception) {
                    Log.e("JarvisService", "Error toggling Vosk pause: ${e.message}")
                }
            }
        }
        try {
            audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
            audioPlaybackCallback = callback
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to register AudioPlaybackCallback: ${e.message}")
        }
    }

    private fun unregisterAudioPlaybackMonitor() {
        val callback = audioPlaybackCallback ?: return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to unregister AudioPlaybackCallback: ${e.message}")
        }
        audioPlaybackCallback = null
    }

    /**
     * Returns true if audio from media (reels, music, YouTube) is actively coming
     * out of the phone's physical loudspeaker, or if a phone/VoIP call is in progress.
     */
    private fun isSpeakerMediaOrCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        // 1. Phone call or VoIP call in progress
        if (audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            return true
        }

        // 2. Active media playback (Instagram reels, YouTube, Spotify, etc.)
        if (audioManager.isMusicActive) {
            val hasHeadset = isHeadsetConnected(audioManager)
            // If NOT connected to headphones/earbuds, sound is blasting from the device speaker into the mic
            if (!hasHeadset) {
                return true
            }
        }

        return false
    }

    private fun isHeadsetConnected(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasHeadsetDevice = devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        @Suppress("DEPRECATION")
        val legacyCheck = audioManager.isWiredHeadsetOn ||
                          audioManager.isBluetoothA2dpOn ||
                          audioManager.isBluetoothScoOn
        return hasHeadsetDevice || legacyCheck
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from Recents. Do NOT stop anything here -
        // that's exactly the case this service exists to survive. The mic,
        // the live session (or standby listener), and the notification all
        // keep running untouched.
        super.onTaskRemoved(rootIntent)
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
