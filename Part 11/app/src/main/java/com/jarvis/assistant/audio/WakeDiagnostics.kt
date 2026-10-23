package com.jarvis.assistant.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short in-memory log of what the wake-word engine heard and why it accepted or
 * rejected it, so "Settings > Wake Words" can show exactly what is going on.
 * Nothing is written to disk.
 */
object WakeDiagnostics {

    private const val MAX = 14
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var finalsProcessed: Int = 0
        private set
    @Volatile var wakesAccepted: Int = 0
        private set

    @Synchronized
    fun record(message: String) {
        lines.addFirst(timeFormat.format(Date()) + "  " + message)
        while (lines.size > MAX) lines.removeLast()
    }

    fun countFinal() {
        finalsProcessed += 1
    }

    fun countAccepted() {
        wakesAccepted += 1
    }

    @Synchronized
    private fun recent(): List<String> = lines.toList()

    fun report(context: Context): String {
        val sb = StringBuilder()
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = try { JarvisApp.instance.preferences } catch (e: Exception) { null }
        val ownerLock = prefs != null && prefs.isOwnerVoiceEnabled && prefs.hasOwnerVoiceProfile()

        sb.append("Microphone permission: ").append(if (micOk) "OK" else "MISSING").append('\n')
        sb.append("Speech model: ").append(if (VoskModelManager.isModelReady.value) "ready" else "loading").append('\n')
        sb.append("Speaker model: ").append(if (VoskModelManager.isSpeakerModelReady.value) "ready" else "loading").append('\n')
        sb.append("Standby listening: ").append(if (JarvisConversationService.isStandby.value) "ON" else "off").append('\n')
        sb.append("Owner voice lock: ").append(if (ownerLock) "ON" else "off").append('\n')
        sb.append("Phrases heard: ").append(finalsProcessed)
            .append("   Wakes: ").append(wakesAccepted).append("\n\n")

        val recent = recent()
        if (recent.isEmpty()) {
            sb.append("Nothing heard yet. Put JARVIS in standby (say \"go to background\"), then say \"Hey Jarvis\" and open this screen again.")
        } else {
            sb.append("Latest activity:\n")
            for (line in recent) sb.append("- ").append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
