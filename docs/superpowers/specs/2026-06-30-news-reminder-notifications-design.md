# News Reminder Notifications — Design

**Date:** 2026-06-30
**Status:** Approved (pending spec review)

## Goal

When the Notifications toggle in Settings is on, periodically remind the user to
read the most recent news. The reminder fires every 2 hours, shows a dynamic
count of new unread stories, and is suppressed overnight.

## Decisions (from brainstorming)

| Decision | Choice |
| --- | --- |
| Notification content | Dynamic unread count ("N new stories") |
| Interval | Every 2 hours |
| Quiet hours | Suppressed 22:00–08:00 local time |
| Toggle default (fresh install) | ON (opt-out) |
| Scheduler | WorkManager periodic work |

## Approach

**WorkManager periodic work.** A `PeriodicWorkRequest` (2h) enqueued under a
unique name. Enqueue when notifications are enabled; cancel when disabled.
WorkManager survives reboot and is Doze-aware, so no `BOOT_COMPLETED` receiver
and no exact-alarm permission are needed.

Rejected: AlarmManager + BroadcastReceiver (manual rescheduling, boot receiver,
more boilerplate); foreground-service loop (battery drain, persistent
notification, overkill).

## Components

### 1. Manifest & Gradle

- `AndroidManifest.xml`: add
  `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`.
- `app/build.gradle`: add `implementation "androidx.work:work-runtime-ktx:2.9.0"`
  (compatible with compileSdk 34 / minSdk 32).

### 2. `NotificationScheduler` (helper object)

New file `notifications/NotificationScheduler.kt`.

- `const val WORK_NAME = "news_reminder"`
- `const val CHANNEL_ID = "news_reminders"`
- `const val INTERVAL_HOURS = 2L`
- `enable(context)` — `ensureChannel(context)` then enqueue:
  `WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME,
  ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<NewsReminderWorker>(2, HOURS).build())`.
  `UPDATE` makes repeat calls idempotent.
- `disable(context)` — `WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)`.
- `ensureChannel(context)` — create `NotificationChannel(CHANNEL_ID, ...)` at
  `IMPORTANCE_DEFAULT`. minSdk 32 ≥ 26 so the channel is always required.

### 3. `NewsReminderWorker : CoroutineWorker`

New file `notifications/NewsReminderWorker.kt`. `doWork()` runs every 2h. Each
guard that fails returns `Result.success()` (skip this cycle, never retry-spam):

1. **Toggle still on?** Read `user_settings.notifications_enabled`; if false, skip.
2. **Quiet hours?** `isQuietHour(LocalTime.now())` true → skip.
3. **Permission?** API 33+: `POST_NOTIFICATIONS` granted? else skip.
4. **New stories?** `since = prefs.getLong("last_reminder_shown_at", now - 24h)`;
   `n = articleDao.countUnreadSince(since)`. If `n == 0`, skip (no nagging when
   nothing is new). DB exception → catch, skip.
5. **Post notification** on `CHANNEL_ID`:
   - title: `"Lumen"`
   - body: `reminderText(n)` → `"1 new story — catch up on the latest news"` /
     `"$n new stories — catch up on the latest news"`.
   - tap: `PendingIntent` (`FLAG_IMMUTABLE`) → `MainActivity`.
6. Persist `last_reminder_shown_at = now`.

Repository/DAO access from the worker: build `NewsRepository`/`LumenDatabase`
the same way existing entry points do (no DI framework in this project).

### 4. Pure helpers (unit-tested)

In `NewsReminderWorker.kt` (or a small `ReminderRules.kt`):

- `isQuietHour(t: LocalTime): Boolean` — true when `t >= 22:00 || t < 08:00`.
- `reminderText(count: Int): String` — singular/plural formatter.

### 5. `ArticleDao` addition

```kotlin
@Query("SELECT COUNT(*) FROM articles WHERE isRead = 0 AND publishedAt > :since")
suspend fun countUnreadSince(since: Long): Int
```

### 6. SettingsActivity wiring (existing Compose switch)

The current `Notifications` switch is `mutableStateOf(true)` and wired to
nothing. Wire it:

- Initial state: `prefs.getBoolean("notifications_enabled", true)` (default ON).
- Toggle ON:
  - API 33+: launch `rememberLauncherForActivityResult(RequestPermission())`
    for `POST_NOTIFICATIONS`. Granted → persist `true` + `NotificationScheduler.enable`.
    Denied → revert switch to false (leave pref false).
  - API <33: persist `true` + `NotificationScheduler.enable` directly.
- Toggle OFF: persist `false` + `NotificationScheduler.disable`.

### 7. First-run / startup handling (MainActivity)

Because the toggle defaults ON but API 33+ cannot post without permission, on
`MainActivity.onCreate`:

- If `notifications_enabled` (default true):
  - `NotificationScheduler.ensureChannel`.
  - API 33+: if `POST_NOTIFICATIONS` not granted, request it once via the
    Activity Result API. On grant → `NotificationScheduler.enable`.
    On denial → leave pref true but no work runs (worker's permission guard
    keeps it silent); user can retoggle later.
  - If already granted (or API <33) → `NotificationScheduler.enable`
    (idempotent via `UPDATE`).

## Data Flow

```
Settings toggle / MainActivity startup
        │  persist notifications_enabled + (request permission)
        ▼
NotificationScheduler.enable ──► WorkManager unique periodic work (2h)
        │ (cancelUniqueWork on disable)
        ▼  every 2h
NewsReminderWorker.doWork
        │  guards: enabled? quiet hours? permission? unread>0?
        ▼
post notification (channel "news_reminders")
        │  tap → PendingIntent
        ▼
MainActivity (feed)
```

## Error Handling

- All worker guard failures → `Result.success()` (skip cycle, no retry storm).
- DB read failure in worker → caught, treated as "skip".
- OS-level permission revocation → worker permission guard no-ops silently.
- Permission denied in Settings → switch reverts, no work scheduled.

## Testing

- Unit (JUnit, matching `ArticleMapperNytTest` style):
  - `isQuietHour` across boundary times (21:59, 22:00, 07:59, 08:00, midday).
  - `reminderText` for 1 vs N.
- Manual / runtime verification: WorkManager enqueue/cancel, channel creation,
  permission flow, actual notification + tap → MainActivity. (Worker/WorkManager
  end-to-end needs an instrumented `work-testing` harness; out of scope for the
  unit suite.)

## Out of Scope (YAGNI)

- User-configurable interval / quiet-hours window.
- Per-topic or per-source reminders.
- Deep-linking the notification to a specific article (tap → feed only).
