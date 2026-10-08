package com.pushrouter.core

import java.security.SecureRandom
import java.util.Base64

object RouterEngine {
    const val RETENTION_MS = 24 * 60 * 60 * 1000L
    const val INVITATION_MS = 5 * 60 * 1000L
    const val MAX_NOTIFICATIONS = 500

    fun matches(route: Route, notification: CapturedNotification): Boolean {
        if (notification.contentUnavailable || notification.forwardingBlockedReason != null) return false
        if (route.packageName != notification.source.packageName || route.profile != notification.source.profile) return false
        if (route.channelId != null && route.channelId != notification.channelId) return false
        val value = when (route.field) {
            TextField.TITLE -> notification.title
            TextField.MESSAGE -> notification.text
            TextField.EITHER -> "${notification.title}\n${notification.text}"
        }
        return route.contains.isBlank() || value.contains(route.contains.trim(), ignoreCase = true)
    }

    fun destinations(state: RouterState, notification: CapturedNotification): Set<String> {
        if (state.paused || state.bot == null) return emptySet()
        val authorized = state.pairings.filter { it.botId == state.bot.id }.map { it.id }.toSet()
        return state.routes.filter { it.enabled && matches(it, notification) }
            .flatMap { it.pairingIds }.toSet().intersect(authorized)
    }

    fun capture(state: RouterState, notification: CapturedNotification): RouterState {
        val clean = prune(state, notification.receivedAt)
        if (clean.notifications.any { it.id == notification.id }) return clean
        val targets = destinations(clean, notification)
        val next = clean.copy(
            notifications = listOf(notification) + clean.notifications,
            deliveries = clean.deliveries + targets.map { pairing ->
                Delivery(notificationId = notification.id, pairingId = pairing, botId = clean.bot!!.id, createdAt = notification.receivedAt)
            },
        )
        return prune(next, notification.receivedAt)
    }

    fun prune(state: RouterState, now: Long): RouterState {
        val notifications = state.notifications.filter { now - it.receivedAt < RETENTION_MS }
            .sortedByDescending { it.receivedAt }.take(MAX_NOTIFICATIONS)
        val ids = notifications.map { it.id }.toSet()
        return state.copy(
            notifications = notifications,
            deliveries = state.deliveries.filter { it.notificationId in ids },
            invitation = state.invitation?.takeIf { now < it.expiresAt },
        )
    }

    fun saveRoute(state: RouterState, route: Route): RouterState {
        require(route.name.isNotBlank()) { "Give this route a name." }
        require(route.pairingIds.isNotEmpty()) { "Choose at least one pairing." }
        val valid = state.pairings.filter { it.botId == state.bot?.id }.map { it.id }.toSet()
        require(route.pairingIds.all { it in valid }) { "A selected pairing is no longer available." }
        return cancelUnauthorized(state.copy(routes = state.routes.filterNot { it.id == route.id } + route.copy(name = route.name.trim())))
    }

    fun removePairing(state: RouterState, id: String): RouterState = cancelUnauthorized(state.copy(
        pairings = state.pairings.filterNot { it.id == id },
        routes = state.routes.map {
            val targets = it.pairingIds - id
            it.copy(pairingIds = targets, enabled = it.enabled && targets.isNotEmpty())
        },
    ))

    fun configureBot(state: RouterState, bot: Bot): RouterState {
        if (state.bot?.id == bot.id) return state.copy(bot = bot, invitation = null)
        return cancelUnauthorized(state.copy(
            bot = bot,
            pairings = emptyList(),
            routes = state.routes.map { it.copy(pairingIds = emptySet(), enabled = false) },
            invitation = null,
            telegramOffset = 0,
        ))
    }

    fun pause(state: RouterState, paused: Boolean): RouterState = cancelUnauthorized(state.copy(paused = paused))

    /** Queued events require current authorization. Resume never replays canceled events. */
    fun canSend(state: RouterState, delivery: Delivery, now: Long): Boolean {
        if (delivery.status != DeliveryStatus.QUEUED || now - delivery.createdAt >= RETENTION_MS) return false
        if (state.bot?.id != delivery.botId) return false
        val n = state.notifications.find { it.id == delivery.notificationId } ?: return false
        return delivery.pairingId in destinations(state, n)
    }

    fun cancelUnauthorized(state: RouterState): RouterState = state.copy(deliveries = state.deliveries.map { d ->
        if (d.status == DeliveryStatus.QUEUED && !canSend(state, d, d.createdAt))
            d.copy(status = DeliveryStatus.CANCELLED, explanation = "Routing authorization changed") else d
    })

    fun createInvitation(state: RouterState, label: String, now: Long): RouterState {
        require(label.isNotBlank()) { "Give this pairing a label." }
        val bot = requireNotNull(state.bot) { "Configure a bot in Settings first." }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return state.copy(invitation = Invitation(token, label.trim(), bot.id, now, now + INVITATION_MS))
    }

    fun proposeRecipient(state: RouterState, token: String, recipient: Recipient, messageTime: Long, now: Long): RouterState {
        val invite = state.invitation ?: return state
        if (invite.botId != state.bot?.id || invite.token != token || now >= invite.expiresAt || invite.recipient != null) return state
        // Private Telegram chat IDs equal the sending user's numeric ID. Never accept a group or forwarded identity.
        if (recipient.userId <= 0 || recipient.chatId != recipient.userId || messageTime < invite.createdAt - 1000) return state
        return state.copy(invitation = invite.copy(recipient = recipient))
    }

    fun confirmRecipient(state: RouterState, now: Long): RouterState {
        val invite = requireNotNull(state.invitation) { "Create another invitation." }
        require(now < invite.expiresAt && invite.botId == state.bot?.id) { "Invitation expired. Create another." }
        val recipient = requireNotNull(invite.recipient) { "Waiting for the recipient to tap Start." }
        require(state.pairings.none { it.botId == invite.botId && it.userId == recipient.userId }) { "This account is already paired." }
        return state.copy(invitation = null, pairings = state.pairings + Pairing(
            label = invite.label, botId = invite.botId, userId = recipient.userId,
            chatId = recipient.chatId, displayName = recipient.displayName, username = recipient.username,
        ))
    }

    fun recoverInterruptedSends(state: RouterState): RouterState = state.copy(deliveries = state.deliveries.map {
        if (it.status == DeliveryStatus.SENDING) it.copy(status = DeliveryStatus.UNKNOWN, explanation = "App stopped while sending; delivery could not be confirmed") else it
    })
}
