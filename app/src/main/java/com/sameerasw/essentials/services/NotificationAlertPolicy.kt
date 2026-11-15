package com.sameerasw.essentials.services

/** Alert policy is separate from the notification list: refreshing content must not re-alert. */
internal class NotificationAlertPolicy(
    private val cooldownMillis: Long = 5_000L,
    private val maxHistory: Int = 500,
) {
    private val history = LinkedHashMap<String, NotificationAlertState>()
    private var lastAlertElapsedMillis: Long? = null

    @Synchronized
    fun onPosted(current: NotificationAlertState, elapsedMillis: Long): Boolean {
        if (current.key.isBlank()) return false
        val previous = history[current.key]
        remember(current)

        if (current.isMedia || current.isSilent || current.isProgress || current.suppressGroupAlert) return false
        if (previous != null) {
            if (current.onlyAlertOnce) return false
            if (current.messageTime > 0L && previous.messageTime > current.messageTime) return false
            if (current.title == previous.title && current.text == previous.text &&
                current.messageTime <= previous.messageTime
            ) return false
        }

        // Use monotonic time; a device clock adjustment must not break burst suppression.
        val last = lastAlertElapsedMillis
        if (last != null && elapsedMillis >= last && elapsedMillis - last < cooldownMillis) return false
        lastAlertElapsedMillis = elapsedMillis
        return true
    }

    /** Restoring or reconciling existing notifications must never play an alert. */
    @Synchronized
    fun replaceHistory(notifications: List<NotificationAlertState>) {
        history.clear()
        // The wire snapshot is newest-first, whereas our history is oldest-first.
        notifications.asReversed().forEach { if (it.key.isNotBlank()) remember(it) }
    }

    @Synchronized
    fun remove(key: String) {
        history.remove(key)
    }

    @Synchronized
    fun snapshot(): List<NotificationAlertState> = history.values.toList().asReversed()

    @Synchronized
    fun restoreCooldown(elapsedMillis: Long?) {
        lastAlertElapsedMillis = elapsedMillis
    }

    @Synchronized
    fun lastAlertTime(): Long? = lastAlertElapsedMillis

    private fun remember(notification: NotificationAlertState) {
        // Do not let an out-of-order messaging event roll back the alert baseline.
        val previous = history[notification.key]
        if (previous != null && notification.messageTime > 0L && previous.messageTime > notification.messageTime) return
        history.remove(notification.key)
        history[notification.key] = notification
        while (history.size > maxHistory) history.remove(history.keys.first())
    }
}

internal data class NotificationAlertState(
    val key: String,
    val title: String,
    val text: String,
    val messageTime: Long = 0L,
    val isMedia: Boolean = false,
    val isSilent: Boolean = false,
    val isProgress: Boolean = false,
    val onlyAlertOnce: Boolean = false,
    val suppressGroupAlert: Boolean = false,
)
