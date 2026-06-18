# Followed Stories — Design Spec
**Date:** 2026-06-18  
**Status:** Approved

---

## Overview

Users can "follow" a clustered article group from the article view. Following creates a **FollowedStory** — an on-device T5-generated summary of all articles in that cluster. The story is updated automatically (append-only) whenever new articles match it. The Stories tab displays only user-followed stories as a timeline card list.

The existing auto-create `StoryMatcher` / `Story` system is removed entirely.

---

## 1. Data Layer

### Removed
- `Story.kt` entity
- `StoryDao.kt`
- `StoryMatcher.kt`
- `Article.storyId` field

### New entities

**`FollowedStory`** (`followed_stories` table)
```kotlin
@Entity(tableName = "followed_stories")
data class FollowedStory(
    @PrimaryKey val id: String,           // UUID
    val title: String,                    // cluster's representativeTitle
    val keywords: String,                 // comma-separated, used for keyword pre-filter
    val centroidEmbedding: String,        // JSON-serialized FloatArray (384-dim MiniLM)
    val summary: String,                  // T5 initial summary text
    val articleUrls: String,              // comma-separated URLs at follow time
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val updateCount: Int = 0
)
```

**`FollowedStoryUpdate`** (`followed_story_updates` table)
```kotlin
@Entity(tableName = "followed_story_updates")
data class FollowedStoryUpdate(
    @PrimaryKey val id: String,           // UUID
    val storyId: String,                  // FK → FollowedStory.id
    val updateText: String,               // T5 summary of new articles only
    val newArticleUrls: String,           // comma-separated new article URLs
    val createdAt: Long
)
```

**`Article`** gains `followedStoryId: String?` (nullable, replaces `storyId`).

### DAOs
- `FollowedStoryDao`: `getAll(): Flow<List<FollowedStory>>`, `getById(id)`, `insert`, `delete`, `updateLastSeen(id, lastUpdatedAt, updateCount)`
- `FollowedStoryUpdateDao`: `getUpdatesForStory(storyId): Flow<List<FollowedStoryUpdate>>`, `insert`

### Room migration
Migration from current schema: drop `stories` table, add `followed_stories` + `followed_story_updates` tables, rename `Article.storyId` → `Article.followedStoryId`.

---

## 2. ML Pipeline

### T5Summarizer (new — `ml/T5Summarizer.kt`)
- Loads three ONNX files from `assets/t5/`:
  - `encoder_model.onnx`
  - `decoder_model.onnx`
  - `decoder_with_past_model.onnx`
- Uses existing ONNX Runtime (no new SDK dependency)
- **Input construction:** for each article, concatenate `"summarize: " + title + " " + body.take(300)`, join articles with ` | `, truncate to 512 tokens
- **Inference:** encoder → decoder greedy decode with KV-cache via `decoder_with_past`, max 150 output tokens
- **Output:** plain summary string
- Called on `Dispatchers.Default`
- **Model source:** `Falconsai/text_summarization` on HuggingFace, exported to ONNX via `optimum-cli export onnx --model Falconsai/text_summarization --task seq2seq-lm ./t5/`
- Place all `.onnx` files in `app/src/main/assets/t5/`; `aaptOptions { noCompress "onnx" }` already configured

### Article matching (runs in `NewsRepository.fetchAndSaveArticles()`)
After saving new articles to Room, for each new article:
1. **Keyword pre-filter:** does the article title contain ≥2 tokens from `FollowedStory.keywords`? If no story matches, skip.
2. **MiniLM similarity:** decode `centroidEmbedding` JSON → FloatArray; compute cosine similarity against article's MiniLM embedding. Threshold: ≥0.50 (same as `ArticleClusterer`).
3. **On match:** set `article.followedStoryId`, upsert to Room. Add to per-story new-articles bucket.

After all articles processed, for each story with ≥1 new article:
- Fetch bodies via `ArticleFetcher`
- Run `T5Summarizer` on new articles only → `updateText`
- Insert `FollowedStoryUpdate`
- Call `updateLastSeen(storyId, now, story.updateCount + 1)`

---

## 3. Follow Flow (user-triggered)

1. User taps bookmark FAB in `ArticleActivity` (cluster not yet followed)
2. App computes MiniLM centroid: mean of all cluster article embeddings, L2-normalized
3. Extracts keywords: top-4 non-stopword tokens from `representativeTitle`
4. Concatenates article titles + bodies → `T5Summarizer` → initial `summary`
5. Creates `FollowedStory`, inserts to DB
6. Updates `Article.followedStoryId` for all cluster articles
7. FAB changes to filled/active state; shows snackbar "Story followed"

Unfollow: tap filled FAB → confirm dialog → delete `FollowedStory` + its `FollowedStoryUpdate` records; clear `followedStoryId` on linked articles.

---

## 4. UI

### ArticleActivity (modified)
- Add yellow bookmark FAB (bottom-right, same yellow `#F5C842` as app theme)
- FAB shows outline when not followed, filled when followed
- FAB hidden while T5 summarization is in progress (show progress indicator instead)
- XML: `activity_article.xml` — add `FloatingActionButton` inside a `CoordinatorLayout`

### StoryFragment (rewritten)
- RecyclerView with `StoryCardAdapter`
- Each card (timeline style):
  - Title (bold white)
  - Source badges (colored pills, same as ArticleActivity)
  - Dot-timeline on right: one dot per update, connected by vertical line, newest dot brightest
  - Summary preview (2 lines, truncated)
  - Footer: update count + last updated timestamp
- Empty state: "No followed stories yet. Follow a story from the article view."
- XML: `fragment_story.xml` + `item_story_card.xml`

### StoryDetailActivity (new)
- Full-screen, same dark theme as `ArticleActivity`
- Toolbar: back button + story title
- Sections:
  - Source badges + "Followed [date]"
  - **Summary** label + initial summary text
  - Divider
  - **Updates** label + list of `FollowedStoryUpdate` cards, each with left-border timeline, date, and update text
- XML: `activity_story_detail.xml`
- Opens from `StoryFragment` card tap; receives `storyId: String` via Intent extra

---

## 5. Files Changed

| File | Action |
|---|---|
| `data/model/FollowedStory.kt` | New |
| `data/model/FollowedStoryUpdate.kt` | New |
| `data/dao/FollowedStoryDao.kt` | New |
| `data/dao/FollowedStoryUpdateDao.kt` | New |
| `ml/T5Summarizer.kt` | New |
| `StoryDetailActivity.kt` | New |
| `res/layout/activity_story_detail.xml` | New |
| `res/layout/item_story_card.xml` | New |
| `fragments/StoryFragment.kt` | Rewrite |
| `res/layout/fragment_story.xml` | Rewrite |
| `ui/ArticleActivity.kt` | Add FAB + follow logic |
| `res/layout/activity_article.xml` | Add FAB |
| `data/NewsRepository.kt` | Add follow/unfollow ops + matching logic |
| `data/LumenDatabase.kt` | Add new DAOs, migration |
| `data/model/Article.kt` | Rename `storyId` → `followedStoryId` |
| `data/model/Story.kt` | **Delete** |
| `data/dao/StoryDao.kt` | **Delete** |
| `ml/StoryMatcher.kt` | **Delete** |
| `assets/t5/encoder_model.onnx` | Add (manual download) |
| `assets/t5/decoder_model.onnx` | Add (manual download) |
| `assets/t5/decoder_with_past_model.onnx` | Add (manual download) |

---

## 6. Out of Scope
- Story sharing or export
- Manual summary regeneration (user-triggered)
- Push notifications for story updates
- Story ordering/sorting options
