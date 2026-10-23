package com.jarvis.assistant.security

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.ui.security.ConfirmActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission gate for risky actions (PC commands, file writes, deleting, plugin calls).
 *
 *  - STRICT (default): every gated action asks the user first.
 *  - NORMAL: only destructive actions (delete, format, shutdown, force push...) ask first.
 * If the user does not answer within 35 seconds the action is DENIED. Every decision is logged.
 */
object SecurityGate {

    const val LEVEL_STRICT = "strict"
    const val LEVEL_NORMAL = "normal"

    private const val PREFS = "jarvis_security"
    private const val KEY_LEVEL = "level"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 60
    private const val WAIT_MS = 35_000L

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val DESTRUCTIVE = Regex(
        "(?i)(\\brm\\b|\\brmdir\\b|\\bdel\\b|\\berase\\b|\\bformat\\b|\\bmkfs\\b|\\bdd\\s+if=|\\bshutdown\\b|\\breboot\\b|" +
            "\\brestart-computer\\b|\\bstop-computer\\b|\\bremove-item\\b|\\breg\\s+delete\\b|\\btaskkill\\b|\\bpkill\\b|" +
            "\\bkillall\\b|\\bkill\\s+-9\\b|\\bchmod\\s+-r\\b|\\bchown\\b|\\bdiskpart\\b|\\bcipher\\s*/w\\b|\\bnet\\s+user\\b|" +
            "\\bsc\\s+delete\\b|\\bgit\\s+push\\s+(-f|--force)|\\bgit\\s+reset\\s+--hard|\\bgit\\s+clean\\b|\\bdrop\\s+(table|database)\\b|" +
            "\\btruncate\\b|\\bmv\\b|\\bmove\\b|\\bmove-item\\b|\\brename\\b|>\\s*/dev/|\\bsudo\\b|\\bcurl\\b[^|]*\\|\\s*(sh|bash)|\\bwget\\b[^|]*\\|\\s*(sh|bash))"
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun level(context: Context): String = prefs(context).getString(KEY_LEVEL, LEVEL_STRICT) ?: LEVEL_STRICT

    fun setLevel(context: Context, level: String) {
        prefs(context).edit().putString(KEY_LEVEL, if (level == LEVEL_NORMAL) LEVEL_NORMAL else LEVEL_STRICT).apply()
    }

    fun isDestructive(text: String): Boolean = DESTRUCTIVE.containsMatchIn(text)

    @Synchronized
    fun log(context: Context, line: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val arr = try { JSONArray(prefs(context).getString(KEY_LOG, "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put("$stamp  $line")
        var i = 0
        while (i < arr.length() && out.length() < MAX_LOG) {
            out.put(arr.optString(i))
            i++
        }
        prefs(context).edit().putString(KEY_LOG, out.toString()).apply()
    }

    @Synchronized
    fun recentLog(context: Context): List<String> {
        val arr = try { JSONArray(prefs(context).getString(KEY_LOG, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }
    }

    @Synchronized
    fun clearLog(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }

    /** Asks the user (on screen) unless the current level lets this action through. */
    suspend fun approve(context: Context, action: String, detail: String, destructive: Boolean): Boolean {
        val app = context.applicationContext
        if (level(app) == LEVEL_NORMAL && !destructive) {
            log(app, "AUTO-ALLOWED: " + action + " - " + detail.take(90))
            return true
        }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        waiting[id] = deferred
        try {
            val intent = Intent(app, ConfirmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(ConfirmActivity.EXTRA_ID, id)
                putExtra(ConfirmActivity.EXTRA_ACTION, action)
                putExtra(ConfirmActivity.EXTRA_DETAIL, detail)
                putExtra(ConfirmActivity.EXTRA_DESTRUCTIVE, destructive)
            }
            app.startActivity(intent)
        } catch (e: Exception) {
            waiting.remove(id)
            log(app, "DENIED (could not ask): " + action + " - " + detail.take(90))
            return false
        }
        val allowed = withTimeoutOrNull(WAIT_MS) { deferred.await() } ?: false
        waiting.remove(id)
        log(app, (if (allowed) "ALLOWED: " else "DENIED: ") + action + " - " + detail.take(90))
        return allowed
    }

    /** Called by ConfirmActivity. */
    fun resolve(id: String, allowed: Boolean) {
        waiting[id]?.complete(allowed)
    }
}
