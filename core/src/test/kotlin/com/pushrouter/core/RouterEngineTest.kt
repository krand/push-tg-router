package com.pushrouter.core

import org.junit.Assert.*
import org.junit.Test

class RouterEngineTest {
    private val now = 1_000_000L
    private val bot = Bot(10, "test_bot", "test-credential")
    private val pairing = Pairing(id = "p1", label = "Personal", botId = 10, userId = 20, chatId = 20, displayName = "Alex", username = null)
    private val other = pairing.copy(id = "p2", userId = 30, chatId = 30, label = "Work")
    private val notification = CapturedNotification("n1", Source("com.bank", "0", "Bank"), "transactions", "Payments", "Payment received", "Acme paid 100", now)
    private val route = Route(id = "r1", name = "Payments", packageName = "com.bank", profile = "0", channelId = "transactions", contains = "PAYMENT", field = TextField.TITLE, pairingIds = setOf("p1"))
    private val state = RouterState(bot = bot, pairings = listOf(pairing, other), routes = listOf(route))

    @Test fun conditionsAreCombinedAndProfileIsNotAnAccountName() {
        assertTrue(RouterEngine.matches(route, notification))
        assertFalse(RouterEngine.matches(route, notification.copy(source = notification.source.copy(profile = "10"))))
        assertFalse(RouterEngine.matches(route, notification.copy(channelId = "marketing")))
        assertFalse(RouterEngine.matches(route, notification.copy(title = "Weekly digest")))
        assertFalse(RouterEngine.matches(route, notification.copy(contentUnavailable = true)))
        assertFalse(RouterEngine.matches(route, notification.copy(forwardingBlockedReason = "Forwarding loop")))
    }
    @Test fun overlappingRoutesAndRepeatedCallbacksSendOneCopyPerDestination() {
        val duplicate = route.copy(id = "r2", pairingIds = setOf("p1", "p2"))
        val first = RouterEngine.capture(state.copy(routes = listOf(route, duplicate)), notification)
        val second = RouterEngine.capture(first, notification)
        assertEquals(2, first.deliveries.size)
        assertEquals(first, second)
    }
    @Test fun unknownOrDifferentBotPairingCannotReceive() {
        val s = state.copy(routes = listOf(route.copy(pairingIds = setOf("missing", "p1"))), pairings = listOf(pairing.copy(botId = 99)))
        assertTrue(RouterEngine.capture(s, notification).deliveries.isEmpty())
    }
    @Test fun removingRecipientCancelsItsQueueAndLeavesOtherRecipientAlone() {
        val s = RouterEngine.capture(state.copy(routes = listOf(route.copy(pairingIds = setOf("p1", "p2")))), notification)
        val removed = RouterEngine.removePairing(s, "p1")
        assertEquals(DeliveryStatus.CANCELLED, removed.deliveries.first { it.pairingId == "p1" }.status)
        assertEquals(DeliveryStatus.QUEUED, removed.deliveries.first { it.pairingId == "p2" }.status)
        assertEquals(setOf("p2"), removed.routes.single().pairingIds)
        assertTrue(removed.routes.single().enabled)
        val last = RouterEngine.removePairing(removed, "p2")
        assertFalse(last.routes.single().enabled)
        assertTrue(last.routes.single().pairingIds.isEmpty())
    }
    @Test fun removingDestinationFromRuleRevokesQueuedAuthorization() {
        val s = RouterEngine.capture(state, notification)
        val edited = RouterEngine.saveRoute(s, route.copy(pairingIds = setOf("p2")))
        assertEquals(DeliveryStatus.CANCELLED, edited.deliveries.single().status)
        // Updating a route does not retroactively add a delivery to the new destination.
        assertEquals(1, edited.deliveries.size)
    }
    @Test fun replacingBotInvalidatesPairingsWhileSameBotRotationPreservesThem() {
        val s = RouterEngine.capture(state, notification)
        val rotation = RouterEngine.configureBot(s, bot.copy(token = "rotated"))
        assertEquals(s.pairings, rotation.pairings)
        assertTrue(RouterEngine.canSend(rotation, rotation.deliveries.single(), now))
        val replacement = RouterEngine.configureBot(s, bot.copy(id = 99))
        assertTrue(replacement.pairings.isEmpty())
        assertFalse(replacement.routes.single().enabled)
        assertEquals(DeliveryStatus.CANCELLED, replacement.deliveries.single().status)
    }
    @Test fun pauseStopsQueueAndResumeDoesNotReplay() {
        val s = RouterEngine.capture(state, notification)
        val paused = RouterEngine.pause(s, true)
        assertEquals(DeliveryStatus.CANCELLED, paused.deliveries.single().status)
        val capturedWhilePaused = RouterEngine.capture(paused, notification.copy(id = "n2"))
        val resumed = RouterEngine.pause(capturedWhilePaused, false)
        assertEquals(1, resumed.deliveries.size)
        assertFalse(RouterEngine.canSend(resumed, resumed.deliveries.single(), now))
    }
    @Test fun invitationsRequirePrivateIdentityMatchingTokenAndUnexpiredApproval() {
        val invite = RouterEngine.createInvitation(state.copy(pairings = emptyList()), "Work", now)
        val token = invite.invitation!!.token
        assertEquals(32, token.length)
        val recipient = Recipient(40, 40, "Marta", null)
        assertNull(RouterEngine.proposeRecipient(invite, "wrong", recipient, now, now).invitation!!.recipient)
        assertNull(RouterEngine.proposeRecipient(invite, token, recipient.copy(chatId = -40), now, now).invitation!!.recipient)
        assertNull(RouterEngine.proposeRecipient(invite, token, recipient, now, now + RouterEngine.INVITATION_MS).invitation!!.recipient)
        val proposed = RouterEngine.proposeRecipient(invite, token, recipient, now, now + 1)
        assertTrue(proposed.pairings.isEmpty())
        val raced = RouterEngine.proposeRecipient(proposed, token, recipient.copy(userId = 50, chatId = 50), now, now + 2)
        assertEquals(40L, raced.invitation!!.recipient!!.userId)
        val approved = RouterEngine.confirmRecipient(proposed, now + 2)
        assertEquals(40L, approved.pairings.single().userId)
        assertNull(approved.invitation)
        assertEquals(approved, RouterEngine.proposeRecipient(approved, token, recipient, now, now + 3))
    }
    @Test(expected = IllegalArgumentException::class) fun approvalAfterExpiryIsRejected() {
        val s = state.copy(invitation = Invitation("token", "Work", bot.id, now, now + 1, Recipient(40, 40, "Marta", null)))
        RouterEngine.confirmRecipient(s, now + 1)
    }
    @Test fun staleHistoryAndQueuesExpireTogether() {
        val s = RouterEngine.capture(state, notification)
        assertFalse(RouterEngine.canSend(s, s.deliveries.single(), now + RouterEngine.RETENTION_MS))
        val pruned = RouterEngine.prune(s, now + RouterEngine.RETENTION_MS)
        assertTrue(pruned.notifications.isEmpty())
        assertTrue(pruned.deliveries.isEmpty())
    }
    @Test fun interruptedSendIsNeverAutomaticallyRetried() {
        val s = RouterEngine.capture(state, notification)
        val interrupted = s.copy(deliveries = s.deliveries.map { it.copy(status = DeliveryStatus.SENDING) })
        val recovered = RouterEngine.recoverInterruptedSends(interrupted)
        assertEquals(DeliveryStatus.UNKNOWN, recovered.deliveries.single().status)
        assertFalse(RouterEngine.canSend(recovered, recovered.deliveries.single(), now))
    }
    @Test fun eventHashKeepsFieldBoundariesAndProfilesDistinct() {
        assertNotEquals(eventId("ab", "c"), eventId("a", "bc"))
        assertNotEquals(eventId("com.bank", "0", "key"), eventId("com.bank", "10", "key"))
    }
}
