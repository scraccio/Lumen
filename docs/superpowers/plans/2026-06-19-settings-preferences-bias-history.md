# Settings: Source Gating, Editable Preferences, Reading History, Bias Toggle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make four onboarding/settings features actually function — fetch only selected outlets, edit preferences via the onboarding screen, a tappable reading-history list, and a working "Show bias meter" toggle.

**Architecture:** Preferences are read/written through `SharedPreferences("user_settings")` (existing source of truth, already read by `FeedFragment`). Source gating happens in `NewsRepository.fetchAndSaveArticles`. The bias toggle is a persisted boolean honored in `ArticleActivity`. Reading history reuses the existing nullable `Article.readAt` column (no DB migration) plus a new Views/XML screen.

**Tech Stack:** Kotlin, Android (mixed Views/XML + Jetpack Compose), Room, Retrofit, coroutines/Flow.

## Global Constraints

- **No unit-test harness exists** for these paths and the code is Android-framework-bound (Activities, SharedPreferences, Room DAO, ONNX). The per-task verification gate is a successful **`gradlew.bat assembleDebug`** (compile + resource/Room codegen) followed by the manual checks listed in each task. Do not fabricate a test framework.
- **New UI ships as XML layouts + Views** (team convention; a colleague converts to declarative). Do not add new Compose screens.
- Preferences keys live in `SharedPreferences("user_settings")`: `topics` (StringSet), `sources` (StringSet), `bias_meter_enabled` (Boolean, default `true`).
- Source display labels are fixed: Guardian articles use `"The Guardian"`, NYT articles use `"New York Times"`. Onboarding stores source *selections* as `"The Guardian"` and `"The New York Times"`.
- Run builds with the Windows wrapper: `gradlew.bat` (PowerShell), not `./gradlew`.
- Package note: `ArticleActivity`, `StoryDetailActivity`, `ReadingHistoryActivity` live in `com.example.lumen.ui`; `StoryAdapter` / `HistoryAdapter` live in `com.example.lumen`.

---

### Task 1: Gate fetchers by selected sources

**Files:**
- Modify: `app/src/main/java/com/example/lumen/data/NewsRepository.kt:137-148`

**Interfaces:**
- Consumes: `SharedPreferences("user_settings")` key `sources` (StringSet); existing `fetchFromGuardian(fromDate, query)` and `fetchFromNyt(query)`.
- Produces: no new public API; `fetchAndSaveArticles` now fetches only selected sources.

- [ ] **Step 1: Gate the two fetchers on the selected-sources set**

Replace `fetchAndSaveArticles` (currently lines 137-148) with:

```kotlin
    suspend fun fetchAndSaveArticles(topic: String? = null) {
        val fromDate = getSevenDaysAgoFormatted()
        val query = topic ?: ""
        val selectedSources = appContext
            .getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .getStringSet("sources", emptySet()) ?: emptySet()

        val guardianArticles =
            if ("The Guardian" in selectedSources) fetchFromGuardian(fromDate, query) else emptyList()
        val nytArticles =
            if ("The New York Times" in selectedSources) fetchFromNyt(query) else emptyList()

        var allArticles = (guardianArticles + nytArticles).distinctBy { it.url }
        // Tag category fetches with the requested topic so the topic-filtered feed
        // query (getAllArticles(topic)) matches — the API section name rarely equals it.
        if (topic != null) allArticles = allArticles.map { it.copy(topic = topic) }
        saveArticles(allArticles)
        matchNewArticlesToStories(allArticles)
    }
```

`android.content.Context` is already imported (line 3) and `appContext` already exists (line 32). No new imports.

- [ ] **Step 2: Build**

Run: `gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Install (`gradlew.bat installDebug`). In onboarding/edit, select **only The Guardian** → feed shows only Guardian articles. Select **only The New York Times** → only NYT. Select an unsupported-only set (e.g. only BBC) → empty feed (expected).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/NewsRepository.kt
git commit -m "feat: fetch only the news sources the user selected"
```

---

### Task 2: Persist and honor the bias-meter toggle

**Files:**
- Modify: `app/src/main/java/com/example/lumen/SettingsActivity.kt` (imports, `SettingsScreen` state, bias switch row)
- Modify: `app/src/main/java/com/example/lumen/ArticleActivity.kt` (biasAnalyzer delegate, `fetchBody`, `onDestroy`)

**Interfaces:**
- Consumes: none.
- Produces: `SharedPreferences("user_settings")` key `bias_meter_enabled` (Boolean, default `true`), written by Settings and read by `ArticleActivity`. Adds `val context = LocalContext.current` inside `SettingsScreen` (consumed by Task 3).

- [ ] **Step 1: Add imports to SettingsActivity**

Add these two imports alongside the existing imports near the top of `SettingsActivity.kt`:

```kotlin
import android.content.Context
import androidx.compose.ui.platform.LocalContext
```

- [ ] **Step 2: Read the persisted flag into Settings state**

Replace the four `var ... by remember` lines at the start of `SettingsScreen` (currently lines 52-55):

```kotlin
    var notifications by remember { mutableStateOf(true) }
    var biasMeter by remember { mutableStateOf(true) }
    var autoDeduplicate by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(false) }
```

with:

```kotlin
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    var notifications by remember { mutableStateOf(true) }
    var biasMeter by remember { mutableStateOf(prefs.getBoolean("bias_meter_enabled", true)) }
    var autoDeduplicate by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Persist on toggle**

Replace the "Show bias meter" row (currently lines 105-109):

```kotlin
        SettingsSwitchRow(
            title = "Show bias meter",
            checked = biasMeter,
            onCheckedChange = { biasMeter = it }
        )
```

with:

```kotlin
        SettingsSwitchRow(
            title = "Show bias meter",
            checked = biasMeter,
            onCheckedChange = {
                biasMeter = it
                prefs.edit().putBoolean("bias_meter_enabled", it).apply()
            }
        )
```

- [ ] **Step 4: Make `biasAnalyzer` a guarded lazy delegate in ArticleActivity**

Replace line 60:

```kotlin
    private val biasAnalyzer: BiasAnalyzer by lazy { BiasAnalyzer(this) }
```

with:

```kotlin
    private val biasAnalyzerDelegate = lazy { BiasAnalyzer(this) }
    private val biasAnalyzer: BiasAnalyzer by biasAnalyzerDelegate
```

- [ ] **Step 5: Gate `fetchBody` on the flag**

Replace the entire `fetchBody` function (currently lines 358-401) with:

```kotlin
    private fun fetchBody(article: Article) {
        val biasEnabled = getSharedPreferences("user_settings", MODE_PRIVATE)
            .getBoolean("bias_meter_enabled", true)

        val cached = bodyCache[article.url]
        if (cached != null) {
            tvBody.text = cached
            progressBody.visibility = View.GONE
            if (biasEnabled) biasCache[article.url]?.let { showBiasResult(it) }
            return
        }

        tvBody.text = ""
        progressBody.visibility = View.VISIBLE
        llBias.visibility = View.GONE
        llBias.alpha = 0f

        // mostra "Calculating bias..." solo se il bias meter è attivo
        if (biasEnabled) {
            tvBiasLoading.visibility = View.VISIBLE
            tvBiasLoading.alpha = 1f
        } else {
            tvBiasLoading.visibility = View.GONE
        }

        currentFetchJob?.cancel()
        currentFetchJob = lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                fetcher.fetchBody(article.url, article.title)
            }

            progressBody.visibility = View.GONE

            if (!body.isNullOrBlank()) {
                bodyCache[article.url] = body
                tvBody.text = body

                if (biasEnabled) {
                    val result = withContext(Dispatchers.Default) {
                        biasAnalyzer.analyze(body)
                    }
                    biasCache[article.url] = result
                    animateBiasIn(result)
                }

            } else {
                tvBody.text = "Could not load article content. Tap 'Read full article' to open in browser."
                if (biasEnabled) {
                    tvBiasLoading.animate().alpha(0f).setDuration(300).withEndAction {
                        tvBiasLoading.visibility = View.GONE
                    }.start()
                }
            }
        }
    }
```

- [ ] **Step 6: Guard the close in `onDestroy`**

Replace `onDestroy` (currently lines 474-479):

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        biasAnalyzer.close()
        if (embedderDelegate.isInitialized()) embedder.close()
        if (t5Delegate.isInitialized()) t5Summarizer.close()
    }
```

with:

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        if (biasAnalyzerDelegate.isInitialized()) biasAnalyzer.close()
        if (embedderDelegate.isInitialized()) embedder.close()
        if (t5Delegate.isInitialized()) t5Summarizer.close()
    }
```

- [ ] **Step 7: Build**

Run: `gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Manual verification**

Settings → toggle **Show bias meter OFF**. Open an article → body loads, **no** bias card and **no** "Calculating bias…". Toggle **ON** → reopen an article → bias card animates in. Confirm the OFF setting survives leaving and reopening Settings.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/lumen/SettingsActivity.kt app/src/main/java/com/example/lumen/ArticleActivity.kt
git commit -m "feat: make Show bias meter toggle gate bias calculation and display"
```

---

### Task 3: Editable preferences via onboarding edit mode

**Files:**
- Modify: `app/src/main/java/com/example/lumen/OnboardingActivity.kt` (companion extra, `onCreate`, `InterestsScreen`, `savePreferences`)
- Modify: `app/src/main/java/com/example/lumen/SettingsActivity.kt` (`SettingsNavigationRow` signature + "Edit topics" wiring)

**Interfaces:**
- Consumes: `OnboardingActivity.EXTRA_EDIT_MODE` (String const), `SharedPreferences("user_settings")` keys `topics`/`sources`, and the `val context` added to `SettingsScreen` in Task 2.
- Produces: `OnboardingActivity.EXTRA_EDIT_MODE = "extra_edit_mode"`; `SettingsNavigationRow(title: String, onClick: () -> Unit = {})` (consumed by Task 5).

- [ ] **Step 1: Add the edit-mode extra and pass it into the screen**

Replace the `OnboardingActivity` class body (currently lines 28-40):

```kotlin
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                InterestsScreen()
            }
        }
    }
}
```

with:

```kotlin
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                InterestsScreen(editMode = editMode)
            }
        }
    }

    companion object {
        const val EXTRA_EDIT_MODE = "extra_edit_mode"
    }
}
```

- [ ] **Step 2: Preload current selections and accept the edit flag**

Replace the `InterestsScreen` signature and its first four lines (currently lines 42-48):

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen() {
    val context = LocalContext.current
    var selectedTopics by remember { mutableStateOf(setOf<String>()) }
    var selectedSources by remember { mutableStateOf(setOf<String>()) }
    var isLoading by remember { mutableStateOf(false) }
```

with:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(editMode: Boolean = false) {
    val context = LocalContext.current
    val savedPrefs = remember {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
    }
    var selectedTopics by remember {
        mutableStateOf(savedPrefs.getStringSet("topics", emptySet())?.toSet() ?: emptySet())
    }
    var selectedSources by remember {
        mutableStateOf(savedPrefs.getStringSet("sources", emptySet())?.toSet() ?: emptySet())
    }
    var isLoading by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Flip the header text in edit mode**

Replace the title `Text` (currently lines 60-65):

```kotlin
        Text(
            text = "your interests",
            fontSize = 32.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFFF5C842)
        )
```

with:

```kotlin
        Text(
            text = if (editMode) "edit interests" else "your interests",
            fontSize = 32.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFFF5C842)
        )
```

- [ ] **Step 4: Pass the edit flag into `savePreferences`**

Replace the `savePreferences(...)` call in the button `onClick` (currently lines 142-146):

```kotlin
                    savePreferences(
                        context = context,
                        topics = selectedTopics,
                        sources = selectedSources
                    )
```

with:

```kotlin
                    savePreferences(
                        context = context,
                        topics = selectedTopics,
                        sources = selectedSources,
                        editMode = editMode
                    )
```

- [ ] **Step 5: Branch `savePreferences` on edit mode**

Replace the whole `savePreferences` function (currently lines 179-199):

```kotlin
fun savePreferences(
    context: Context,
    topics: Set<String>,
    sources: Set<String>
) {
    context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("topics", topics)
        .putStringSet("sources", sources)
        .apply()

    context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboarding_done", true)
        .apply()

    val activity = context as android.app.Activity
    activity.startActivity(Intent(context, MainActivity::class.java))
    @Suppress("DEPRECATION")
    activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
}
```

with:

```kotlin
fun savePreferences(
    context: Context,
    topics: Set<String>,
    sources: Set<String>,
    editMode: Boolean = false
) {
    context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("topics", topics)
        .putStringSet("sources", sources)
        .apply()

    val activity = context as android.app.Activity

    if (editMode) {
        // Editing existing prefs from Settings — overwrite and return.
        // The feed re-reads SharedPreferences the next time it loads.
        activity.finish()
        return
    }

    context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboarding_done", true)
        .apply()

    activity.startActivity(Intent(context, MainActivity::class.java))
    @Suppress("DEPRECATION")
    activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
}
```

The bottom `InterestsScreenPreview` keeps calling `InterestsScreen()` — valid via the `editMode = false` default. No change needed.

- [ ] **Step 6: Add an import to SettingsActivity**

Add this import alongside the existing imports in `SettingsActivity.kt`:

```kotlin
import android.content.Intent
```

- [ ] **Step 7: Give `SettingsNavigationRow` an onClick parameter**

Replace the `SettingsNavigationRow` signature and its clickable `Row` opening (currently lines 184-192):

```kotlin
@Composable
fun SettingsNavigationRow(title: String) {

    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {  }
                .padding(vertical = 18.dp),
```

with:

```kotlin
@Composable
fun SettingsNavigationRow(title: String, onClick: () -> Unit = {}) {

    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 18.dp),
```

- [ ] **Step 8: Wire the "Edit topics" row**

Replace the three navigation rows (currently lines 133-135):

```kotlin
        SettingsNavigationRow("Edit topics")
        SettingsNavigationRow("Manage sources")
        SettingsNavigationRow("Reading history")
```

with:

```kotlin
        SettingsNavigationRow("Edit topics") {
            val intent = Intent(context, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_EDIT_MODE, true)
            context.startActivity(intent)
        }
        SettingsNavigationRow("Manage sources")
        SettingsNavigationRow("Reading history")
```

(`context` is the `val context = LocalContext.current` added in Task 2. `OnboardingActivity` is in the same `com.example.lumen` package — no import needed. "Reading history" is wired in Task 5.)

- [ ] **Step 9: Build**

Run: `gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Manual verification**

Settings → **Edit topics** → onboarding screen opens titled "edit interests" with the user's current topics and sources **preselected**. Deselect one topic and toggle one source, tap **CONTINUE** → returns to Settings (no full app restart). Reopen Edit topics → the change persisted. Open the feed → reflects the new selection.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/example/lumen/OnboardingActivity.kt app/src/main/java/com/example/lumen/SettingsActivity.kt
git commit -m "feat: edit topics/sources by reopening onboarding in edit mode"
```

---

### Task 4: Reading-history data layer (mark-as-read + query)

**Files:**
- Modify: `app/src/main/java/com/example/lumen/data/dao/ArticleDao.kt:17-18` and after line 43
- Modify: `app/src/main/java/com/example/lumen/data/NewsRepository.kt:52-54` (and add a query passthrough)
- Modify: `app/src/main/java/com/example/lumen/ArticleActivity.kt` (`displayArticle`)

**Interfaces:**
- Consumes: existing `Article.readAt: Long?` column (already on the entity; DB at version 2 with `fallbackToDestructiveMigration` — no migration needed); existing `repository` field and `lifecycleScope` in `ArticleActivity`.
- Produces: `ArticleDao.markAsRead(url: String, readAt: Long)`, `ArticleDao.getReadArticles(): Flow<List<Article>>`, `NewsRepository.getReadArticles(): Flow<List<Article>>` (consumed by Task 5). `NewsRepository.markAsRead(url: String)` keeps its existing one-arg signature.

- [ ] **Step 1: Update the DAO**

Replace the `markAsRead` query (currently lines 17-18):

```kotlin
    @Query("UPDATE articles SET isRead = 1 WHERE url = :url")
    suspend fun markAsRead(url: String)
```

with:

```kotlin
    @Query("UPDATE articles SET isRead = 1, readAt = :readAt WHERE url = :url")
    suspend fun markAsRead(url: String, readAt: Long)
```

Then add this query directly after `getAllArticles` (currently lines 42-43):

```kotlin
    @Query("SELECT * FROM articles WHERE isRead = 1 ORDER BY readAt DESC")
    fun getReadArticles(): Flow<List<Article>>
```

(`Flow` is already imported in this file.)

- [ ] **Step 2: Update the repository**

Replace `markAsRead` (currently lines 52-54):

```kotlin
    suspend fun markAsRead(url: String) {
        articleDao.markAsRead(url)
    }
```

with:

```kotlin
    suspend fun markAsRead(url: String) {
        articleDao.markAsRead(url, System.currentTimeMillis())
    }

    fun getReadArticles(): Flow<List<Article>> = articleDao.getReadArticles()
```

(`Flow` and `Article` are already imported in this file.)

- [ ] **Step 3: Mark articles read when shown**

In `ArticleActivity.displayArticle` (currently starts at line 316), add a mark-as-read call immediately after `val article = articles[index]`:

```kotlin
    private fun displayArticle(index: Int) {
        val article = articles[index]

        lifecycleScope.launch { repository.markAsRead(article.url) }

        tvTitle.text = article.title
```

(`lifecycleScope` and `repository` already exist in this class.)

- [ ] **Step 4: Build**

Run: `gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL` (Room regenerates the DAO with the new query signatures).

- [ ] **Step 5: Manual verification**

Build/install. Open an article. (The visible history UI lands in Task 5; for now confirm the app still builds and articles open normally — no regression.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/dao/ArticleDao.kt app/src/main/java/com/example/lumen/data/NewsRepository.kt app/src/main/java/com/example/lumen/ArticleActivity.kt
git commit -m "feat: record readAt and expose read-articles query for history"
```

---

### Task 5: Reading-history screen

**Files:**
- Create: `app/src/main/java/com/example/lumen/HistoryAdapter.kt`
- Create: `app/src/main/java/com/example/lumen/ui/ReadingHistoryActivity.kt`
- Create: `app/src/main/res/layout/activity_reading_history.xml`
- Create: `app/src/main/res/layout/item_history_article.xml`
- Modify: `app/src/main/AndroidManifest.xml:45-47` (register the activity)
- Modify: `app/src/main/java/com/example/lumen/SettingsActivity.kt` ("Reading history" row wiring)

**Interfaces:**
- Consumes: `NewsRepository.getReadArticles(): Flow<List<Article>>` (Task 4); `ArticleActivity.EXTRA_URLS` / `EXTRA_START_INDEX`; `SettingsNavigationRow(title, onClick)` (Task 3); existing drawable `ic_arrow_back` and colors `@color/dark_blue`, `@color/yellow`.
- Produces: `ReadingHistoryActivity`, launched from the Settings "Reading history" row.

- [ ] **Step 1: Create the item layout**

Create `app/src/main/res/layout/item_history_article.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="12dp"
    android:layout_marginBottom="10dp"
    android:clickable="true"
    android:focusable="true"
    app:cardBackgroundColor="#1E3A5F"
    app:cardCornerRadius="10dp"
    app:cardElevation="2dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="14dp">

        <TextView
            android:id="@+id/tv_history_title"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:textStyle="bold"
            android:textColor="#FFFFFF"
            android:maxLines="3"
            android:ellipsize="end"
            android:layout_marginBottom="8dp"/>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                android:id="@+id/tv_history_source"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="10sp"
                android:textColor="#FFFFFF"
                android:paddingHorizontal="10dp"
                android:paddingVertical="3dp"/>

            <TextView
                android:id="@+id/tv_history_date"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="10dp"
                android:textSize="11sp"
                android:textColor="#888888"/>

        </LinearLayout>

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```

- [ ] **Step 2: Create the screen layout**

Create `app/src/main/res/layout/activity_reading_history.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/dark_blue">

    <androidx.appcompat.widget.Toolbar
        android:id="@+id/history_toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/dark_blue">

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <ImageButton
                android:id="@+id/btn_back"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:src="@drawable/ic_arrow_back"
                android:contentDescription="Back"
                android:background="?attr/selectableItemBackgroundBorderless"/>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:text="Reading history"
                android:textColor="@color/yellow"
                android:textSize="20sp"/>

        </LinearLayout>

    </androidx.appcompat.widget.Toolbar>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/yellow"/>

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rv_history"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:paddingTop="8dp"
            android:clipToPadding="false"/>

        <TextView
            android:id="@+id/tv_history_empty"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="No articles read yet."
            android:textColor="#666666"
            android:textSize="14sp"
            android:visibility="gone"/>

    </FrameLayout>

</LinearLayout>
```

- [ ] **Step 3: Create the adapter**

Create `app/src/main/java/com/example/lumen/HistoryAdapter.kt`:

```kotlin
package com.example.lumen

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.data.model.Article
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onArticleClick: (Article) -> Unit
) : ListAdapter<Article, HistoryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Article>() {
            override fun areItemsTheSame(a: Article, b: Article) = a.url == b.url
            override fun areContentsTheSame(a: Article, b: Article) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvSource: TextView = view.findViewById(R.id.tv_history_source)
        val tvDate: TextView = view.findViewById(R.id.tv_history_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_article, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val article = getItem(position)
        holder.tvTitle.text = article.title
        holder.tvSource.text = article.source
        holder.tvSource.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(sourceColor(article.source))
        }
        holder.tvDate.text = formatDate(article.readAt ?: article.publishedAt)
        holder.itemView.setOnClickListener { onArticleClick(article) }
    }

    private fun sourceColor(source: String): Int = when (source) {
        "The Guardian" -> Color.parseColor("#005689")
        "New York Times" -> Color.parseColor("#000000")
        else -> Color.parseColor("#666666")
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
```

- [ ] **Step 4: Create the activity**

Create `app/src/main/java/com/example/lumen/ui/ReadingHistoryActivity.kt`:

```kotlin
package com.example.lumen.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.HistoryAdapter
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.network.ArticleFetcher
import kotlinx.coroutines.launch

class ReadingHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_history)

        tvEmpty = findViewById(R.id.tv_history_empty)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        adapter = HistoryAdapter { article ->
            val intent = Intent(this, ArticleActivity::class.java).apply {
                putStringArrayListExtra(ArticleActivity.EXTRA_URLS, arrayListOf(article.url))
                putExtra(ArticleActivity.EXTRA_START_INDEX, 0)
            }
            startActivity(intent)
        }

        findViewById<RecyclerView>(R.id.rv_history).apply {
            layoutManager = LinearLayoutManager(this@ReadingHistoryActivity)
            adapter = this@ReadingHistoryActivity.adapter
        }

        val db = LumenDatabase.getInstance(this)
        val repository = NewsRepository(
            db.articleDao(),
            db.followedStoryDao(),
            db.followedStoryUpdateDao(),
            db.userPrefsDao(),
            ArticleFetcher(),
            this
        )

        lifecycleScope.launch {
            repository.getReadArticles().collect { articles ->
                adapter.submitList(articles)
                tvEmpty.visibility = if (articles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
```

The Room `Flow` is reactive, so returning from `ArticleActivity` (which marks the article read) updates the list automatically — no manual refresh needed.

- [ ] **Step 5: Register the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, add the new activity next to the other `.ui.` activities (currently lines 45-47):

```xml
        <activity android:name=".ui.ArticleActivity"/>
        <activity android:name=".ui.StoryDetailActivity"/>
        <activity android:name=".ui.ReadingHistoryActivity"/>
        <activity android:name=".SettingsActivity"/>
```

- [ ] **Step 6: Wire the "Reading history" row in Settings**

In `SettingsActivity.kt`, replace the `SettingsNavigationRow("Reading history")` line (from Task 3 this is the third nav row) with:

```kotlin
        SettingsNavigationRow("Reading history") {
            context.startActivity(
                Intent(context, com.example.lumen.ui.ReadingHistoryActivity::class.java)
            )
        }
```

(`context` and the `android.content.Intent` import already exist from Tasks 2 and 3.)

- [ ] **Step 7: Build**

Run: `gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Manual verification**

Install. Open a couple of articles, then back out. Settings → **Reading history** → the read articles appear, most-recently-read first; the source badge and date show. Tap one → opens its detail page. With a fresh install (nothing read), the screen shows "No articles read yet."

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/lumen/HistoryAdapter.kt app/src/main/java/com/example/lumen/ui/ReadingHistoryActivity.kt app/src/main/res/layout/activity_reading_history.xml app/src/main/res/layout/item_history_article.xml app/src/main/AndroidManifest.xml app/src/main/java/com/example/lumen/SettingsActivity.kt
git commit -m "feat: add Reading history screen listing read articles"
```

---

## Self-Review

**Spec coverage:**
- Selected outlets only → Task 1. ✓
- Edit topics reopens onboarding, preloaded, overwrites → Task 3. ✓
- Reading history list → detail page, true read-order → Tasks 4 (readAt + query + mark-on-open) and 5 (screen). ✓
- Bias toggle gates calculation + display, persisted, default on → Task 2 (incl. the `onDestroy` force-init fix). ✓
- Out-of-scope items (other switches, Manage sources, Room `UserPrefs`) → untouched. ✓

**Type consistency:**
- `markAsRead(url, readAt)` defined in Task 4 DAO, called from repo (Task 4); repo `markAsRead(url)` keeps one-arg signature for the existing `ArticleActivity` caller (Task 4 Step 3). ✓
- `getReadArticles(): Flow<List<Article>>` defined in Task 4 (DAO + repo), consumed in Task 5. ✓
- `SettingsNavigationRow(title, onClick = {})` defined in Task 3, consumed in Tasks 3 and 5. ✓
- `OnboardingActivity.EXTRA_EDIT_MODE` defined in Task 3 Step 1, used in Task 3 Step 8. ✓
- `val context` added in Task 2 Step 2, consumed in Tasks 3 and 5. ✓ (Sequential ordering required: 2 → 3 → 5.)
- `biasAnalyzerDelegate` defined in Task 2 Step 4, used in Steps 5–6. ✓

**Cross-task ordering:** Tasks must run in order (2 before 3 before 5 for the shared `context`/imports in `SettingsActivity`; 4 before 5 for `getReadArticles`). Noted above.

**Placeholder scan:** No TBD/TODO/placeholder steps; every code step shows complete code.
