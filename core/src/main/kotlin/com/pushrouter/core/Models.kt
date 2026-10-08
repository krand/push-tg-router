package com.pushrouter.core

import java.security.MessageDigest
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

data class Source(val packageName: String, val profile: String, val appName: String)
data class CapturedNotification(
    val id: String,
    val source: Source,
    val channelId: String,
    val categoryName: String,
    val title: String,
    val text: String,
    val receivedAt: Long,
    val contentUnavailable: Boolean = false,
    val forwardingBlockedReason: String? = null,
)
enum class TextField { TITLE, MESSAGE, EITHER }
data class Route(
    val id: String = newId(),
    val name: String,
    val packageName: String,
    val profile: String,
    val channelId: String? = null,
    val field: TextField = TextField.EITHER,
    val contains: String = "",
    val pairingIds: Set<String> = emptySet(),
    val enabled: Boolean = true,
)
data class Bot(val id: Long, val username: String, val token: String, val revision: String = newId())
data class Pairing(
    val id: String = newId(),
    val label: String,
    val botId: Long,
    val userId: Long,
    val chatId: Long,
    val displayName: String,
    val username: String?,
)
data class Recipient(val userId: Long, val chatId: Long, val displayName: String, val username: String?)
data class Invitation(
    val token: String,
    val label: String,
    val botId: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val recipient: Recipient? = null,
)
enum class DeliveryStatus { QUEUED, SENDING, SENT, FAILED, UNKNOWN, CANCELLED }
data class Delivery(
    val id: String = newId(),
    val notificationId: String,
    val pairingId: String,
    val botId: Long,
    val createdAt: Long,
    val status: DeliveryStatus = DeliveryStatus.QUEUED,
    val messageId: Long? = null,
    val explanation: String? = null,
    val nextAttemptAt: Long = 0,
)
data class RouterState(
    val schemaVersion: Int = 1,
    val bot: Bot? = null,
    val paused: Boolean = false,
    val notifications: List<CapturedNotification> = emptyList(),
    val routes: List<Route> = emptyList(),
    val pairings: List<Pairing> = emptyList(),
    val deliveries: List<Delivery> = emptyList(),
    val invitation: Invitation? = null,
    val telegramOffset: Long = 0,
)

/** Include profile and platform notification key, not display labels, in event identity. */
fun eventId(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
