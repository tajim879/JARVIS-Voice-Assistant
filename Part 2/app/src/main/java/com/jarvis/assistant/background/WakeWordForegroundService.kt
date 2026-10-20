package com.jarvis.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import java.util.Locale

/**
 * Keeps the app process and microphone permission alive after the user has
 * explicitly put Jarvis in standby.  Only the platform recognizer receives
 * standby audio; Gemini is disconnected until a wake phrase is detected.
 */
class WakeWordForegroundService : Service(), RecognitionListener {
    companion object {
        const val ACTION_START = "com.jarvis.assistant.START_WAKE_LISTENER"
        const val ACTION_ACTIVE = "com.jarvis.assistant.ACTIVE_CONVERSATION"
        const val ACTION_STOP = "com.jarvis.assistant.STOP_WAKE_LISTENER"
        const val ACTION_WAKE_DETECTED = "com.jarvis.assistant.WAKE_DETECTED"
        const val EXTRA_ANNOUNCE = "announce_background"
        private const val CHANNEL_ID = "jarvis_wake_listener"
        private const val NOTIFICATION_ID = 41
    }

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var shouldListen = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        shouldListen = intent?.action == ACTION_START
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WakeListener")
            .also { it.acquire(10 * 60 * 1000L) }
        if (shouldListen && intent?.getBooleanExtra(EXTRA_ANNOUNCE, false) == true) {
            announceAndListen()
        } else if (shouldListen) {
            startListening()
        } else {
            recognizer?.destroy()
            recognizer = null
        }
        // Android may recreate a user-started service after memory pressure.
        return START_STICKY
    }

    private fun startListening() {
        if (!shouldListen || recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Use on-device recognition when the device supports it.  Android
                // may fall back to its configured recognizer otherwise.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }

    private fun announceAndListen() {
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) { restartListening() }
                    override fun onDone(utteranceId: String?) { restartListening() }
                })
                tts?.speak(
                    "Sir, I am going in the background. If you need me, call me.",
                    TextToSpeech.QUEUE_FLUSH, null, "background-announcement"
                )
            } else restartListening()
        }
    }

    private fun restartListening() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        if (shouldListen) android.os.Handler(mainLooper).postDelayed({ startListening() }, 350)
    }

    private fun check(results: Bundle?) {
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (phrases.any(::containsWakeWord)) {
            shouldListen = false
            sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
            stopSelf()
        }
    }

    private fun containsWakeWord(phrase: String): Boolean {
        val words = phrase.lowercase(Locale.ROOT)
        return Regex("\\b(hey\\s+)?jarvis\\b").containsMatchIn(words) ||
            Regex("\\bjarvis\\s+(wake\\s+up|wakeup)\\b").containsMatchIn(words)
    }

    override fun onResults(results: Bundle?) { check(results); restartListening() }
    override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
    override fun onError(error: Int) { restartListening() }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        shouldListen = false
        recognizer?.destroy()
        recognizer = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis background listening", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Jarvis is in the background")
        .setContentText("Say \"Hey Jarvis\" to wake me up")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .addAction(0, "Stop", PendingIntent.getService(
            this, 0, Intent(this, WakeWordForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .build()
}
