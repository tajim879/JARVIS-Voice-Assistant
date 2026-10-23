package com.jarvis.assistant.pro

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.WebSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** One reminder or scheduled task. type = "reminder" (notification) or "task" (Jarvis answers a question). */
data class ScheduledItem(
    val id: String,
    val type: String,
    val text: String,
    val atMillis: Long,
    val repeat: String // none, daily or weekly
)

object ScheduleStore {
    private const val PREFS = "jarvis_schedule"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): MutableList<ScheduledItem> {
        val out = ArrayList<ScheduledItem>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ScheduledItem(
                        o.getString("id"), o.optString("type", "reminder"), o.optString("text", ""),
                        o.optLong("at", 0L), o.optString("repeat", "none")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("ScheduleStore", "load failed: " + e.message)
        }
        return out.sortedBy { it.atMillis }.toMutableList()
    }

    @Synchronized
    private fun save(context: Context, items: List<ScheduledItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().put("id", it.id).put("type", it.type).put("text", it.text)
                    .put("at", it.atMillis).put("repeat", it.repeat)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun put(context: Context, item: ScheduledItem) {
        val items = load(context)
        items.removeAll { it.id == item.id }
        items.add(item)
        save(context, items)
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val items = load(context)
        val changed = items.removeAll { it.id == id }
        if (changed) save(context, items)
        return changed
    }

    @Synchronized
    fun find(context: Context, id: String): ScheduledItem? = load(context).firstOrNull { it.id == id }
}

object ScheduleManager {
    const val ACTION_FIRE = "com.jarvis.assistant.SCHEDULE_FIRE"
    const val EXTRA_ID = "schedule_id"
    private const val CHANNEL_ID = "jarvis_reminders"

    fun create(context: Context, type: String, text: String, atMillis: Long, repeat: String): ScheduledItem {
        val cleanRepeat = if (repeat == "daily" || repeat == "weekly") repeat else "none"
        val item = ScheduledItem(UUID.randomUUID().toString().take(8), type, text, atMillis, cleanRepeat)
        ScheduleStore.put(context, item)
        schedule(context, item)
        return item
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("jarvis://schedule/" + id)
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, item: ScheduledItem) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, item.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
        }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
        ScheduleStore.remove(context, id)
    }

    /** Next time a repeating item should fire after "after". */
    fun nextOccurrence(item: ScheduledItem, after: Long): Long {
        if (item.repeat == "none") return item.atMillis
        val cal = Calendar.getInstance()
        cal.timeInMillis = item.atMillis
        val step = if (item.repeat == "weekly") Calendar.WEEK_OF_YEAR else Calendar.DAY_OF_YEAR
        while (cal.timeInMillis <= after) cal.add(step, 1)
        return cal.timeInMillis
    }

    /** After a reboot or an app update every saved item must be armed again. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (item in ScheduleStore.load(context)) {
            if (item.atMillis > now) {
                schedule(context, item)
            } else if (item.repeat != "none") {
                val next = item.copy(atMillis = nextOccurrence(item, now))
                ScheduleStore.put(context, next)
                schedule(context, next)
            } else {
                // Missed while the phone was off: fire shortly instead of silently dropping it.
                schedule(context, item.copy(atMillis = now + 4000L))
            }
        }
    }

    fun notify(context: Context, title: String, body: String, notificationId: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_jarvis)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull().orEmpty().take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(notificationId, n)
        } catch (e: Exception) {
            Log.w("ScheduleManager", "notify failed: " + e.message)
        }
    }
}

object ScheduleRunner {
    suspend fun run(context: Context, id: String) {
        val item = ScheduleStore.find(context, id) ?: return
        if (item.type == "task") {
            val r = WebSearchEngine.search(item.text, false)
            val body = if (r.error == null) r.answer else "Could not finish this task: " + r.error
            ScheduleManager.notify(context, "Jarvis: " + item.text.take(40), body, item.id.hashCode())
        } else {
            ScheduleManager.notify(context, "Reminder", item.text, item.id.hashCode())
        }
        if (item.repeat != "none") {
            val next = item.copy(atMillis = ScheduleManager.nextOccurrence(item, System.currentTimeMillis()))
            ScheduleStore.put(context, next)
            ScheduleManager.schedule(context, next)
        } else {
            ScheduleStore.remove(context, item.id)
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ScheduleManager.EXTRA_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduleRunner.run(app, id)
            } catch (e: Exception) {
                Log.w("ScheduleReceiver", "run failed: " + e.message)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ScheduleManager.rescheduleAll(context.applicationContext)
        }
    }
}
