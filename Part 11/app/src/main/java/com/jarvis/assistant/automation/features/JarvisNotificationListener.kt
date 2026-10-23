package com.jarvis.assistant.automation.features

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Collects incoming notifications (only after the user turns on Notification access for JARVIS
 * in system settings) so the assistant can read them out loud on request.
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val notification = n.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val label = try {
            val info = packageManager.getApplicationInfo(n.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            n.packageName
        }
        NotificationStore.add(NotificationEntry(label, n.packageName, title, text, n.postTime))
    }
}
