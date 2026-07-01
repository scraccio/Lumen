package com.example.lumen.notifications

import java.time.LocalTime

/**
 * Pure, framework-free rules for the news reminder. Kept separate from the
 * WorkManager worker so they can be unit-tested without an Android runtime.
 */
object ReminderRules {

    val QUIET_START: LocalTime = LocalTime.of(22, 0)
    val QUIET_END: LocalTime = LocalTime.of(8, 0)

    /** True when [t] falls inside the quiet window [22:00, 08:00). */
    fun isQuietHour(t: LocalTime): Boolean = t >= QUIET_START || t < QUIET_END

    /** Notification body for [count] new unread stories. Caller guards count > 0. */
    fun reminderText(count: Int): String {
        val noun = if (count == 1) "new story" else "new stories"
        return "$count $noun — catch up on the latest news"
    }
}
