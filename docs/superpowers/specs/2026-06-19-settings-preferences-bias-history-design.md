# Settings: Source Gating, Editable Preferences, Reading History, Bias Toggle

Date: 2026-06-19
Status: Approved (pending spec review)

## Goal

Make four onboarding/settings features actually work:

1. Only the news outlets the user selected during onboarding are fetched.
2. The "Edit topics" settings row reopens the onboarding screen, preloaded with current
   preferences, and overwrites them on save.
3. "Reading history" shows a tappable list of articles the user has read, opening the
   article detail page.
4. The "Show bias meter" toggle gates whether bias is calculated and displayed.

Preferences live in `SharedPreferences("user_settings")` (keys `topics`, `sources` as
`StringSet`). This is the existing source of truth — `FeedFragment` already reads it. The
Room `UserPrefs` table is not used for topics/sources and is left untouched.

## 1. Selected outlets only

**Current:** `NewsRepository.fetchAndSaveArticles()` always calls both `fetchFromGuardian`
and `fetchFromNyt`, ignoring the user's source selection. Only Guardian and NYT have real
fetchers; the other 5 onboarding sources (BBC, Reuters, Der Spiegel, Politico, Al Jazeera)
have no implementation.

**Change:** Gate the two fetchers on the selected-sources set, read from
`SharedPreferences("user_settings")` (repo already holds `appContext`):

- `"The Guardian"` in selected set → call `fetchFromGuardian`, else skip.
- `"The New York Times"` in selected set → call `fetchFromNyt`, else skip.
- The 5 unsupported sources are no-ops (no fetcher exists). Per product decision, the full
  7-source list stays in onboarding.
- Empty/absent selection → no fetch → empty feed. Accepted as consistent with the no-op
  behavior above.

Gating decides *which fetcher to call*; article `source` labels are unchanged, so the
existing `"New York Times"` vs onboarding `"The New York Times"` naming difference does not
matter here.

**Files:** `NewsRepository.kt`.

## 2. Edit topics → onboarding edit mode

**Current:** The "Edit topics" row has an empty `clickable { }`. `InterestsScreen` starts
with empty selections and always launches `MainActivity` on save.

**Change:**

- Add `OnboardingActivity.EXTRA_EDIT_MODE` (boolean intent extra). "Edit topics" row in
  `SettingsActivity` launches `OnboardingActivity` with the flag `true`.
- `InterestsScreen` preloads `selectedTopics` / `selectedSources` from
  `SharedPreferences("user_settings")` on first composition. Harmless on first run (empty
  sets). This lets the user edit both topics and sources.
- Header text reads "edit interests" in edit mode, "your interests" otherwise.
- `savePreferences` branches on edit mode:
  - **Edit mode:** overwrite `topics` + `sources`, then `finish()` back to Settings. Feed
    re-reads prefs the next time it loads.
  - **First run (unchanged):** overwrite prefs, set `onboarding_done = true`, launch
    `MainActivity`.
- The `EXTRA_EDIT_MODE` flag must reach the `@Composable` (e.g. read from the Activity
  intent and pass into `InterestsScreen` as a parameter).
- "Manage sources" row is now redundant (onboarding edits sources too) and is left as a
  no-op. Out of scope.

**Files:** `OnboardingActivity.kt`, `SettingsActivity.kt`.

## 3. Reading history

**Current:** The "Reading history" row is an empty `clickable { }`. Nothing ever calls
`markAsRead`, so `isRead` is always `0`. The `Article` entity already has a nullable
`readAt: Long?` column (DB version 2, `fallbackToDestructiveMigration`) — **no new column
or migration needed**.

**Change:**

- **Mark read on open:** `ArticleActivity.displayArticle()` calls `repository.markAsRead(url)`
  for the shown article (including when switching source tabs).
- **DAO:** change `markAsRead` to also set `readAt`:
  `UPDATE articles SET isRead = 1, readAt = :readAt WHERE url = :url`, called with
  `System.currentTimeMillis()`. Add
  `getReadArticles(): Flow<List<Article>>` =
  `SELECT * FROM articles WHERE isRead = 1 ORDER BY readAt DESC`.
- **Repo:** `markAsRead(url)` passes the timestamp; add `getReadArticles()` passthrough.
- **New screen `ReadingHistoryActivity`** (Views + XML, per team convention that new UI
  ships as XML layouts):
  - `activity_reading_history.xml` — back button / title bar + `RecyclerView`. Empty-state
    text when the list is empty.
  - `item_history_article.xml` — card showing title, source badge, formatted date (styled
    like the existing story update / cluster rows).
  - `HistoryAdapter` — binds `List<Article>`; row tap → `ArticleActivity` with
    `EXTRA_URLS = arrayListOf(url)`, `EXTRA_START_INDEX = 0`.
  - Collects `repository.getReadArticles()` and reloads on resume.
- "Reading history" row in `SettingsActivity` launches `ReadingHistoryActivity`.
- Must be registered in `AndroidManifest.xml`.

**Files:** `ArticleActivity.kt`, `ArticleDao.kt`, `NewsRepository.kt`, new
`ReadingHistoryActivity.kt`, new `HistoryAdapter.kt`, new `activity_reading_history.xml`,
new `item_history_article.xml`, `AndroidManifest.xml`.

## 4. Bias meter toggle

**Current:** `SettingsActivity.biasMeter` is local Compose state, persisted nowhere and read
nowhere. `ArticleActivity` always computes and displays bias.

**Change:**

- Persist a `bias_meter_enabled` boolean in `SharedPreferences("user_settings")`, default
  `true` (preserves current behavior). `SettingsActivity` initializes the switch from it and
  writes on toggle.
- `ArticleActivity.fetchBody()` gates on the flag:
  - **Enabled:** current behavior (load body, analyze, animate bias card in).
  - **Disabled:** skip `biasAnalyzer.analyze()` entirely; keep the bias card (`ll_bias`) and
    "Calculating bias…" (`tv_bias_loading`) hidden. Body still loads normally.
- **Fix:** convert `biasAnalyzer` to a guarded `lazy` delegate (mirroring `embedder` /
  `t5Summarizer`) so `onDestroy()` only calls `close()` when it was actually initialized —
  otherwise closing force-initializes the analyzer even when bias was off.

**Files:** `SettingsActivity.kt`, `ArticleActivity.kt`.

## Out of scope

- Other settings switches (Notifications, Auto-deduplicate, Dark mode) stay non-functional.
- "Manage sources" row stays a no-op.
- The unused Room `UserPrefs` table.

## Testing

Manual (no existing unit-test coverage for these paths):

1. **Sources:** select only Guardian in onboarding → feed shows only Guardian articles;
   select only NYT → only NYT; select an unsupported-only set → empty feed.
2. **Edit:** Settings → Edit topics shows current selections preselected; deselect a topic,
   save → returns to Settings; feed reflects the change on next load.
3. **History:** open an article → appears at the top of Reading history; tapping it reopens
   the detail page; order is most-recently-read first.
4. **Bias:** toggle off → article detail loads body with no bias card and no "Calculating
   bias…"; toggle on → bias card animates in as before.
