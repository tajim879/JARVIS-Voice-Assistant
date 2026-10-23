package com.jarvis.assistant.automation.features

import java.util.Locale

data class NotificationEntry(
    val app: String,
    val packageName: String,
    val title: String,
    val text: String,
    val time: Long
)

/** In-memory only: nothing is written to disk and it is cleared when the app process ends. */
object NotificationStore {

    private const val MAX = 50
    private val items = ArrayDeque<NotificationEntry>()

    @Synchronized
    fun add(entry: NotificationEntry) {
        items.addFirst(entry)
        while (items.size > MAX) items.removeLast()
    }

    @Synchronized
    fun recent(limit: Int, appFilter: String?): List<NotificationEntry> {
        val f = appFilter?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val list = if (f.isEmpty()) items.toList() else items.filter {
            it.app.lowercase(Locale.ROOT).contains(f) || it.packageName.lowercase(Locale.ROOT).contains(f)
        }
        return list.take(limit.coerceIn(1, 20))
    }

    @Synchronized
    fun clear() = items.clear()
}
