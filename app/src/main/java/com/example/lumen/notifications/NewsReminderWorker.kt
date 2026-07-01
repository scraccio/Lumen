package com.example.lumen.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.lumen.MainActivity
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import java.time.LocalTime

/**
 * Fires every 2 hours (scheduled by [NotificationScheduler]). Each guard that
 * fails returns success — skip this cycle, never retry-spam.
 */
class NewsReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val prefs = applicationContext.getSharedPreferences(
        NotificationScheduler.PREFS, Context.MODE_PRIVATE
    )

    override suspend fun doWork(): Result {
        // 1. Toggle still on?
        if (!prefs.getBoolean(NotificationScheduler.KEY_ENABLED, true)) return Result.success()

        // 2. Quiet hours?
        if (ReminderRules.isQuietHour(LocalTime.now())) return Result.success()

        // 3. Permission (API 33+)?
        if (!hasNotificationPermission()) return Result.success()

        // 4. New unread stories since last reminder?
        val since = prefs.getLong(
            NotificationScheduler.KEY_LAST_SHOWN,
            System.currentTimeMillis() - DEFAULT_WINDOW_MS
        )
        val count = try {
            LumenDatabase.getInstance(applicationContext).articleDao().countUnreadSince(since)
        } catch (e: Exception) {
            return Result.success()
        }
        if (count <= 0) return Result.success()

        // 5. Post.
        postReminder(count)

        // 6. Record so the next cycle counts only newer articles.
        prefs.edit().putLong(NotificationScheduler.KEY_LAST_SHOWN, System.currentTimeMillis()).apply()

        return Result.success()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun postReminder(count: Int) {
        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lumen")
            .setContentText(ReminderRules.reminderText(count))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
