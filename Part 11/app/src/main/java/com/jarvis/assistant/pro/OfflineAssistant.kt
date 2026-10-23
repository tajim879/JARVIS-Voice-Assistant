package com.jarvis.assistant.pro

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.audio.VoskModelManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline Mode: a small set of English voice commands that work with NO internet, using the
 * on-device Vosk model and the phone's own text-to-speech. It is used automatically when you say the
 * wake word while the phone is offline, and can be tried from Settings > Pro Capabilities.
 */
object OfflineAssistant {

    private const val TAG = "OfflineAssistant"
    private const val PREFS = "jarvis_offline"

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "sixty" to 60
    )

    private val APP_WORDS = listOf(
        "camera", "settings", "chrome", "youtube", "maps", "gallery", "photos", "calculator",
        "contacts", "phone", "messages", "clock", "files", "gmail", "facebook", "instagram",
        "telegram", "spotify", "browser", "email", "music"
    )

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when a wake word should start the offline helper instead of the (internet) Gemini session. */
    fun shouldUseOffline(context: Context): Boolean =
        isEnabled(context) && !isOnline(context) && VoskModelManager.model != null

    private fun grammar(): String {
        val phrases = ArrayList<String>()
        phrases += listOf(
            "flashlight on", "flashlight off", "torch on", "torch off",
            "what time is it", "what is the time", "what is the date", "tell me the time", "today date",
            "battery", "battery level", "volume up", "volume down", "mute",
            "go home", "stop", "cancel", "timer one hour"
        )
        for (app in APP_WORDS) phrases += "open $app"
        for (word in NUMBER_WORDS.keys) phrases += "timer $word minutes"
        phrases += "[unk]"
        val arr = JSONArray()
        phrases.forEach { arr.put(it) }
        return arr.toString()
    }

    /** Listens once (about 7 seconds), runs the command, speaks the answer, then calls onDone. */
    fun listenOnce(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        val model = VoskModelManager.model
        if (model == null) {
            speak(app, "The offline voice model is not ready yet.") { onDone() }
            return
        }
        val service: SpeechService
        try {
            val recognizer = Recognizer(model, 16000.0f, grammar())
            service = SpeechService(recognizer, 16000.0f)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start offline listening: " + e.message)
            speak(app, "I could not start listening.") { onDone() }
            return
        }
        var finished = false
        fun finish(text: String?) {
            if (finished) return
            finished = true
            try {
                service.stop()
                service.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: " + e.message)
            }
            if (text == null || text.isBlank()) {
                speak(app, "I did not catch that.") { onDone() }
            } else {
                val reply = handle(app, text)
                speak(app, reply) { onDone() }
            }
        }
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}

            override fun onResult(hypothesis: String?) {
                val text = try { JSONObject(hypothesis ?: "{}").optString("text", "") } catch (e: Exception) { "" }
                if (text.isNotBlank() && text != "[unk]") finish(text)
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = try { JSONObject(hypothesis ?: "{}").optString("text", "") } catch (e: Exception) { "" }
                finish(if (text == "[unk]") "" else text)
            }

            override fun onError(exception: Exception?) {
                finish("")
            }

            override fun onTimeout() {
                finish("")
            }
        }, 7000)
    }

    /** Runs a recognised phrase and returns the sentence to speak back. */
    fun handle(context: Context, phrase: String): String {
        val text = phrase.lowercase(Locale.ROOT).trim()
        Log.i(TAG, "offline phrase: " + text)
        return when {
            text == "stop" || text == "cancel" || text == "go home" -> {
                if (text == "go home") {
                    launch(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    "Going home."
                } else {
                    "Okay."
                }
            }
            text.contains("flashlight") || text.contains("torch") -> torch(context, text.endsWith("on"))
            text.startsWith("timer") -> timer(context, text)
            text.contains("time") -> "It is " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + "."
            text.contains("date") -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date()) + "."
            text.contains("battery") -> {
                val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level >= 0 && scale > 0) "Battery is at " + (level * 100 / scale) + " percent." else "I could not read the battery."
            }
            text.startsWith("volume") || text == "mute" -> volume(context, text)
            text.startsWith("open ") -> openApp(context, text.removePrefix("open ").trim())
            else -> "Sorry, offline I only understand a few commands like flashlight, time, battery, volume, timer and open an app."
        }
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun torch(context: Context, on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                val c = cm.getCameraCharacteristics(cid)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                "This phone has no flashlight."
            } else {
                cm.setTorchMode(id, on)
                if (on) "Flashlight on." else "Flashlight off."
            }
        } catch (e: Exception) {
            "I could not control the flashlight."
        }
    }

    private fun volume(context: Context, text: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            text == "mute" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Muted."
            }
            text.endsWith("up") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Volume up."
            }
            else -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Volume down."
            }
        }
    }

    private fun timer(context: Context, text: String): String {
        val seconds = if (text.contains("hour")) {
            3600
        } else {
            val word = text.split(" ").firstOrNull { NUMBER_WORDS.containsKey(it) }
            (NUMBER_WORDS[word] ?: 0) * 60
        }
        if (seconds <= 0) return "How long should the timer be?"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return if (launch(context, intent)) "Timer set for " + (seconds / 60) + " minutes." else "I could not set the timer."
    }

    private fun openApp(context: Context, name: String): String {
        val special: Intent? = when (name) {
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "phone" -> Intent(Intent.ACTION_DIAL)
            "messages" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            else -> null
        }
        if (special != null && launch(context, special)) return "Opening $name."

        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, PackageManager.MATCH_ALL).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(name)
        }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (intent != null && launch(context, intent)) return "Opening $name."
        }
        return "I could not find $name on this phone."
    }

    // ------------------------------------------------------------------ speaking

    private var tts: TextToSpeech? = null

    private fun speak(context: Context, text: String, onDone: () -> Unit) {
        var ready = false
        var completed = false
        fun done() {
            if (completed) return
            completed = true
            try { tts?.shutdown() } catch (e: Exception) { /* ignore */ }
            tts = null
            onDone()
        }
        val engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                done()
                return@TextToSpeech
            }
            ready = true
            val t = tts
            if (t == null) {
                done()
                return@TextToSpeech
            }
            t.language = Locale.US
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done() }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_offline")
        }
        tts = engine
        // Safety net: never leave the engine open if speaking stalls.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!ready || !completed) done() }, 12_000L)
    }
}
