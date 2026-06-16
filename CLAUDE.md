# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

All commands use the Gradle wrapper from the project root:

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.example.lumen.ExampleUnitTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Architecture Overview

Lumen is an Android news aggregator with on-device ML for bias detection and semantic clustering. The app uses a hybrid UI approach: **Views (XML + Fragments)** for the main feed and article reader, and **Jetpack Compose** for onboarding and settings screens.

### Activity / Screen Flow

```
SplashActivity
  ├─► OnboardingActivity (Compose)  — first launch only, saves to SharedPreferences "lumen"
  └─► MainActivity (Views)
        ├─ FeedFragment     — article feed with topic filter chips
        ├─ StoryFragment    — story tracking view
        └─ DashboardFragment — reading stats
```

`SplashActivity` checks `SharedPreferences("lumen").getBoolean("onboarding_done")` to decide whether to show onboarding. `OnboardingActivity` saves selected topics and sources to `SharedPreferences("user_settings")` and sets `onboarding_done = true` in `SharedPreferences("lumen")`.

### Data Layer

Room database (`LumenDatabase`) with three entities:
- **`Article`** — primary key is `url`; stores title, source, topic, timestamps, bias scores (`biasLabel`, `biasLeftScore`, `biasCenterScore`, `biasRightScore`), `storyId` (foreign reference), `isRead` state, and `fetchedAt` for cache control.
- **`UserPrefs`** — single-row table (id=1); `topics` and `sources` are comma-separated strings.
- **`Story`** — groups related articles; matched by keyword overlap (≥2 keyword matches against title).

`NewsRepository` is the single access point for all data operations. It is instantiated directly in `MainActivity` and `FeedFragment` (no DI framework), then passed to `FeedViewModel` via `FeedViewModelFactory`.

### Network Layer

Two Retrofit services in `RetrofitClient` (singleton):
- **Guardian API** (`GuardianApiService`) — fetches articles with `fields=thumbnail` for images.
- **NYT API** (`NytApiService`) — fetches articles; body content is reconstructed from `lead_paragraph`, `abstract`, and `snippet` fields since full text isn't in the API response.

`ArticleFetcher` handles body scraping: Guardian and NYT use their respective APIs/Jsoup; fallback uses Jsoup with paragraph heuristics (>100 chars).

`ArticleMapper` converts `GuardianArticle` / `NytArticle` network models to the `Article` Room entity.

### ML Pipeline (on-device ONNX)

All models live in `app/src/main/assets/` and are loaded via ONNX Runtime. The `aaptOptions { noCompress "onnx" }` in `build.gradle` prevents compression of ONNX files.

1. **`MiniLMEmbedder`** — loads `minilm.onnx`, produces 384-dim sentence embeddings via mean-pooling over the last hidden state, then L2-normalises. Uses `MiniLMTokenizer` (WordPiece, max 128 tokens).

2. **`ArticleClusterer`** — greedy cosine-similarity clustering with threshold `0.50f`. Articles with similarity ≥ 0.50 are grouped into an `ArticleCluster`. Clusters are sorted by most-recent article. Called on `Dispatchers.Default` in `FeedViewModel`.

3. **`BiasAnalyzer`** — loads `bias_bert.onnx`, runs BERT tokenization (`BiasBertTokenizer`, max 128 tokens), outputs softmax over `[left, center, right]` labels. Called in `ArticleActivity` after body text is fetched; results are displayed as animated bars.

4. **`StoryMatcher`** — keyword-based (not ML); extracts top-4 non-stopword tokens from article title and matches against existing `Story` records (≥2 keyword hits = same story).

### Feed → Display Flow

1. `FeedFragment` creates `FeedViewModel` with a `NewsRepository` and `ArticleClusterer`.
2. `FeedViewModel.loadArticles()` calls `repository.fetchAndSaveArticles()` (network → Room), then collects `getAllArticles()` as a `Flow<List<Article>>`, clusters the result, and emits `List<ArticleCluster>` to `_clusters`.
3. `ClusterAdapter` renders clusters; tapping a cluster opens `ArticleActivity` with an `ArrayList<String>` of article URLs via `EXTRA_URLS`.
4. `ArticleActivity` loads `Article` objects from Room by URL, fetches body text via `ArticleFetcher`, runs `BiasAnalyzer` on the body, and animates the bias bar.

### Key Design Notes

- **Cache freshness**: `NewsRepository.isCacheFresh()` checks for articles fetched within the last hour. Articles older than 30 days are pruned; stories are never deleted.
- **API keys are hardcoded** in `NewsRepository` and `ArticleFetcher` (Guardian and NYT keys). Both classes have duplicate copies of the same keys — keep them in sync if rotating.
- **Mixed UI paradigm**: `MainActivity`, `FeedFragment`, `ArticleActivity`, `StoryFragment`, and `DashboardFragment` use traditional Views with XML layouts. `OnboardingActivity` and `SettingsActivity` use Compose. `SettingsActivity` currently duplicates `SplashActivity` logic (it redirects to main/onboarding instead of showing settings) — this is a known incomplete state.
- **Package inconsistency**: `FeedFragment` and `FeedViewModel` are declared in package `com.example.lumen.ui.fragments` but their files live under `fragments/`. `ArticleActivity`, `ClusterAdapter`, and `ArticleAdapter` are in `com.example.lumen.ui` but some are imported from `com.example.lumen` in places — check imports carefully when adding new files.
