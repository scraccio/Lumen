package com.example.lumen.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager periodic work and notification channel for news reminders.
 * No DI in this project, so callers invoke these statics directly.
 */
object NotificationScheduler {

    const val WORK_NAME = "news_reminder"
    const val CHANNEL_ID = "news_reminders"
    const val PREFS = "user_settings"
    const val KEY_ENABLED = "notifications_enabled"
    const val KEY_LAST_SHOWN = "last_reminder_shown_at"
    private const val INTERVAL_HOURS = 2L

    /** Enqueue (idempotently) the 2-hour periodic reminder. */
    fun enable(context: Context) {
        ensureChannel(context)
        val request = PeriodicWorkRequestBuilder<NewsReminderWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Cancel the periodic reminder. */
    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Create the reminder notification channel. minSdk 32 ≥ 26, so always required. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "News reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Periodic reminders to read the latest news"
        }
        manager.createNotificationChannel(channel)
    }
}
