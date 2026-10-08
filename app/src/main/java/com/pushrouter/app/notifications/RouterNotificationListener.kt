package com.pushrouter.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pushrouter.app.RouterApplication
import com.pushrouter.core.CapturedNotification
import com.pushrouter.core.Source
import com.pushrouter.core.eventId
import kotlinx.coroutines.launch

class RouterNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val application = application as RouterApplication
        val repo = application.repository ?: return
        val notification = sbn.notification
        val extras = notification.extras ?: android.os.Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(500)
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(3000)
        // Android redaction is also localized. Compare the platform string when exposed, without reflection.
        val hiddenId = resources.getIdentifier("redacted_notification_message", "string", "android")
        val hidden = if (hiddenId != 0) resources.getString(hiddenId) else "Sensitive notification content hidden"
        val unavailable = (title.isBlank() && text.isBlank()) || text == hidden || title == hidden
            || text == "Sensitive notification content hidden"
        val serial = runCatching { getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user) }.getOrDefault(-1L)
        val profile = if (serial >= 0) serial.toString() else sbn.user.toString()
        val channel = notification.channelId.orEmpty()
        val categoryName = runCatching { getNotificationChannels(sbn.packageName, sbn.user).firstOrNull { it.id == channel }?.name?.toString() }.getOrNull()
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        // Telegram receipts can echo routed notifications back to this phone. Keep supported Telegram clients local.
        val isTelegram = sbn.packageName in setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram")
        val record = CapturedNotification(
            id = eventId(sbn.packageName, profile, sbn.key, channel, title, text),
            source = Source(sbn.packageName, profile, appName),
            channelId = channel,
            categoryName = categoryName ?: channel.ifBlank { notification.category ?: "Notifications" },
            title = title, text = text, receivedAt = System.currentTimeMillis(), contentUnavailable = unavailable,
            forwardingBlockedReason = if (isTelegram) "Telegram notifications are kept local to prevent forwarding loops" else null,
        )
        application.scope.launch { runCatching { repo.capture(record) } }
    }
}
