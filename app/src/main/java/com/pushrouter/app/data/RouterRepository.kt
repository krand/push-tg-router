package com.pushrouter.app.data

import android.content.Context
import com.pushrouter.app.delivery.ForwardWorker
import com.pushrouter.app.telegram.TelegramClient
import com.pushrouter.app.telegram.TelegramException
import com.pushrouter.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class RouterRepository(private val context: Context, private val client: TelegramClient = TelegramClient()) {
    private val storage = EncryptedStateFile(context)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(RouterEngine.prune(RouterEngine.recoverInterruptedSends(storage.read()), System.currentTimeMillis()))
    val state = mutable.asStateFlow()

    private fun commit(next: RouterState) { storage.write(next); mutable.value = next }
    suspend fun update(transform: (RouterState) -> RouterState) = withContext(Dispatchers.IO) {
        lock.withLock { val next = transform(mutable.value); if (next != mutable.value) commit(next) }
        scheduleQueued()
    }
    fun scheduleQueued() { mutable.value.deliveries.filter { it.status == DeliveryStatus.QUEUED }.forEach { ForwardWorker.schedule(context, it.id) } }
    suspend fun capture(n: CapturedNotification) = update { RouterEngine.capture(it, n) }
    suspend fun saveRoute(r: Route) = update { RouterEngine.saveRoute(it, r) }
    suspend fun deleteRoute(id: String) = update { RouterEngine.cancelUnauthorized(it.copy(routes = it.routes.filterNot { r -> r.id == id })) }
    suspend fun toggleRoute(id: String, enabled: Boolean) = update { s ->
        RouterEngine.cancelUnauthorized(s.copy(routes = s.routes.map { if (it.id == id) it.copy(enabled = enabled && it.pairingIds.isNotEmpty()) else it }))
    }
    suspend fun pause(paused: Boolean) = update { RouterEngine.pause(it, paused) }
    suspend fun renamePairing(id: String, label: String) = update { s ->
        require(label.isNotBlank()) { "Give this pairing a label." }
        s.copy(pairings = s.pairings.map { if (it.id == id) it.copy(label = label.trim()) else it })
    }
    suspend fun removePairing(id: String) = update { RouterEngine.removePairing(it, id) }
    suspend fun verifyBot(token: String): Bot = withContext(Dispatchers.IO) { client.verify(token.trim()) }
    suspend fun configureBot(bot: Bot) = update { RouterEngine.configureBot(it, bot) }
    suspend fun invite(label: String) = update { RouterEngine.createInvitation(it, label, System.currentTimeMillis()) }
    suspend fun cancelInvitation() = update { it.copy(invitation = null) }
    suspend fun confirmRecipient() = update { RouterEngine.confirmRecipient(it, System.currentTimeMillis()) }
    suspend fun prune() = update { RouterEngine.prune(it, System.currentTimeMillis()) }
    suspend fun clearHistory() = update { it.copy(notifications = emptyList(), deliveries = emptyList()) }

    suspend fun pollInvitation() = withContext(Dispatchers.IO) {
        val before = mutable.value
        val bot = before.bot ?: return@withContext
        val invitation = before.invitation ?: return@withContext
        if (invitation.recipient != null || System.currentTimeMillis() >= invitation.expiresAt) return@withContext
        val updates = client.updates(bot, before.telegramOffset)
        if (updates.isEmpty()) return@withContext
        lock.withLock {
            var next = mutable.value
            if (next.bot?.revision != bot.revision) return@withLock
            updates.forEach { u ->
                if (u.startToken != null && u.recipient != null) next = RouterEngine.proposeRecipient(next, u.startToken, u.recipient, u.date, System.currentTimeMillis())
                next = next.copy(telegramOffset = maxOf(next.telegramOffset, u.id + 1))
            }
            commit(next)
        }
    }

    /** Serializes authorization changes with send initiation. Already in-flight messages cannot be recalled. */
    suspend fun deliver(id: String): Long? = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = RouterEngine.prune(mutable.value, System.currentTimeMillis())
            val delivery = s.deliveries.find { it.id == id } ?: return@withLock null
            if (!RouterEngine.canSend(s, delivery, System.currentTimeMillis())) {
                if (delivery.status == DeliveryStatus.QUEUED) commit(changeDelivery(s, id, DeliveryStatus.CANCELLED, "Routing authorization changed"))
                return@withLock null
            }
            if (delivery.nextAttemptAt > System.currentTimeMillis()) {
                return@withLock maxOf(1, (delivery.nextAttemptAt - System.currentTimeMillis() + 999) / 1000)
            }
            val bot = s.bot!!
            val pairing = s.pairings.first { it.id == delivery.pairingId }
            val notification = s.notifications.first { it.id == delivery.notificationId }
            commit(changeDelivery(s, id, DeliveryStatus.SENDING))
            try {
                val messageId = client.send(bot, pairing.chatId, notification)
                commit(mutable.value.copy(deliveries = mutable.value.deliveries.map { if (it.id == id) it.copy(status = DeliveryStatus.SENT, messageId = messageId) else it }))
                null
            } catch (e: TelegramException) {
                if (e.code == 429) {
                    commit(mutable.value.copy(deliveries = mutable.value.deliveries.map {
                        if (it.id == id) it.copy(status = DeliveryStatus.QUEUED, explanation = "Waiting for Telegram rate limit",
                            nextAttemptAt = System.currentTimeMillis() + maxOf(1, e.retryAfter) * 1000) else it
                    }))
                    maxOf(1, e.retryAfter)
                } else {
                    val status = if (e.code >= 500) DeliveryStatus.UNKNOWN else DeliveryStatus.FAILED
                    commit(changeDelivery(mutable.value, id, status, e.message))
                    null
                }
            } catch (_: IOException) {
                // Telegram sendMessage has no idempotency key. An uncertain response is not safe to auto-retry.
                commit(changeDelivery(mutable.value, id, DeliveryStatus.UNKNOWN, "Connection interrupted; delivery could not be confirmed"))
                null
            }
        }
    }

    private fun changeDelivery(s: RouterState, id: String, status: DeliveryStatus, reason: String? = null) = s.copy(
        deliveries = s.deliveries.map { if (it.id == id) it.copy(status = status, explanation = reason) else it },
    )
}
