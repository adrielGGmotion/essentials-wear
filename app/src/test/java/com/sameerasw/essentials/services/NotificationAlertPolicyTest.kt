package com.sameerasw.essentials.services

import org.junit.Assert.*
import org.junit.Test

class NotificationAlertPolicyTest {
    private val notification = NotificationAlertState("key", "Sender", "Hello")

    @Test fun identicalUpdatesDoNotAlertAgainAfterCooldown() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification, 0))
        repeat(10) { assertFalse(policy.onPosted(notification, (it + 1) * 10_000L)) }
    }

    @Test fun changingProgressNeverAlertsButCompletionCan() {
        val policy = NotificationAlertPolicy()
        repeat(10) {
            assertFalse(policy.onPosted(notification.copy(text = "$it%", isProgress = true), it * 10_000L))
        }
        assertTrue(policy.onPosted(notification.copy(text = "Download complete"), 100_000L))
    }

    @Test fun onlyAlertOnceIsHonoredAcrossContentChanges() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification.copy(onlyAlertOnce = true), 0))
        assertFalse(policy.onPosted(notification.copy(text = "Updated", onlyAlertOnce = true), 10_000))
    }

    @Test fun silentMediaAndGroupSuppressedContentStillUpdatesBaseline() {
        listOf(
            notification.copy(isSilent = true),
            notification.copy(isMedia = true),
            notification.copy(suppressGroupAlert = true),
        ).forEach { suppressed ->
            val policy = NotificationAlertPolicy()
            assertFalse(policy.onPosted(suppressed, 0))
            assertFalse(policy.onPosted(notification, 10_000))
            assertEquals(1, policy.snapshot().size)
        }
    }

    @Test fun newMessagesReusingAKeyCanAlert() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification.copy(messageTime = 100), 0))
        assertTrue(policy.onPosted(notification.copy(text = "Next message", messageTime = 200), 5_000))
        assertTrue(policy.onPosted(notification.copy(text = "Next message", messageTime = 300), 10_000))
    }

    @Test fun olderMessagesDoNotRollBackBaselineOrAlert() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification.copy(messageTime = 200), 0))
        assertFalse(policy.onPosted(notification.copy(text = "Older", messageTime = 100), 10_000))
        assertFalse(policy.onPosted(notification.copy(messageTime = 200), 20_000))
        assertEquals(200L, policy.snapshot().single().messageTime)
    }

    @Test fun burstIsLimitedButEveryNotificationIsRemembered() {
        val policy = NotificationAlertPolicy()
        val alerts = (0 until 40).count { policy.onPosted(notification.copy(key = "key-$it"), it * 100L) }
        assertEquals(1, alerts)
        assertEquals(40, policy.snapshot().size)
        assertTrue(policy.onPosted(notification.copy(key = "after-cooldown"), 5_000))
    }

    @Test fun cooldownSuppressedChangesDoNotAlertOnRetry() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification, 0))
        val updated = notification.copy(text = "Next message")
        assertFalse(policy.onPosted(updated, 1_000))
        assertFalse(policy.onPosted(updated, 10_000))
    }

    @Test fun silentSnapshotsAndProcessRestorationSeedHistory() {
        val policy = NotificationAlertPolicy()
        policy.replaceHistory(listOf(notification))
        assertFalse(policy.onPosted(notification, 0))
        assertTrue(policy.onPosted(notification.copy(text = "New message"), 1))
        val restored = NotificationAlertPolicy()
        restored.replaceHistory(policy.snapshot())
        assertFalse(restored.onPosted(notification.copy(text = "New message"), 10_000))
    }

    @Test fun snapshotDoesNotResetBurstCooldown() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification, 0))
        policy.replaceHistory(listOf(notification))
        assertFalse(policy.onPosted(notification.copy(key = "other"), 1_000))
    }

    @Test fun restoredCooldownStillLimitsDifferentNotificationKeys() {
        val original = NotificationAlertPolicy()
        assertTrue(original.onPosted(notification, 10_000))
        val restored = NotificationAlertPolicy()
        restored.replaceHistory(original.snapshot())
        restored.restoreCooldown(original.lastAlertTime())
        assertFalse(restored.onPosted(notification.copy(key = "other"), 11_000))
        assertTrue(restored.onPosted(notification.copy(key = "later"), 15_000))
    }

    @Test fun removedNotificationsCanAlertWhenPostedAgain() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification, 0))
        policy.remove(notification.key)
        assertTrue(policy.onPosted(notification, 10_000))
    }

    @Test fun historySurvivesTheThirtyRowDisplayLimit() {
        val policy = NotificationAlertPolicy()
        repeat(40) { policy.onPosted(notification.copy(key = "key-$it", onlyAlertOnce = true), it * 5_000L) }
        assertFalse(policy.onPosted(notification.copy(key = "key-0", text = "Changed", onlyAlertOnce = true), 300_000))
    }

    @Test fun historyIsBoundedAndKeepsMostRecentlyUpdatedKeys() {
        val policy = NotificationAlertPolicy(maxHistory = 2)
        policy.onPosted(notification.copy(key = "a"), 0)
        policy.onPosted(notification.copy(key = "b"), 1)
        policy.onPosted(notification.copy(key = "a"), 2)
        policy.onPosted(notification.copy(key = "c"), 3)
        assertEquals(listOf("c", "a"), policy.snapshot().map { it.key })
    }

    @Test fun monotonicClockResetDoesNotSuppressForever() {
        val policy = NotificationAlertPolicy()
        assertTrue(policy.onPosted(notification, 100_000))
        assertTrue(policy.onPosted(notification.copy(key = "after-reset"), 1))
    }

    @Test fun blankKeysAreNotAlertedOrRemembered() {
        val policy = NotificationAlertPolicy()
        assertFalse(policy.onPosted(notification.copy(key = ""), 0))
        assertTrue(policy.snapshot().isEmpty())
    }
}
