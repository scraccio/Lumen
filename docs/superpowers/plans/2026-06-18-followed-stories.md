# Followed Stories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users follow a clustered article group from ArticleActivity, creating a persisted on-device T5-summarized story that auto-updates when new matching articles arrive, displayed in a fully implemented StoryFragment.

**Architecture:** Replace the auto-created `Story` system entirely with user-triggered `FollowedStory` entities. Following a cluster embeds all article titles via MiniLM to compute a centroid, then runs T5-small (ONNX, encoder+decoder) to generate the initial summary. On each fetch, new articles are matched against followed stories via keyword pre-filter then MiniLM cosine similarity (≥0.50), and T5 generates append-only update summaries.

**Tech Stack:** Kotlin, Room (version bump with fallbackToDestructiveMigration), ONNX Runtime (existing), T5-small ONNX (encoder_model.onnx + decoder_model.onnx in assets/t5/), MiniLMEmbedder (existing), Material FAB, RecyclerView.

---

## Prerequisites (manual steps before coding)

- [ ] Copy `tokenizer.json` from your `t5_summarization_onnx/` export output into `app/src/main/assets/t5/`
  - This file is needed by `T5Tokenizer` to load the vocabulary
- [ ] Verify `app/src/main/assets/t5/` contains: `encoder_model.onnx`, `decoder_model.onnx`, `tokenizer.json`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `data/model/FollowedStory.kt` | Create | Room entity for a user-followed story |
| `data/model/FollowedStoryUpdate.kt` | Create | Room entity for each append-only update |
| `data/dao/FollowedStoryDao.kt` | Create | DB operations for FollowedStory |
| `data/dao/FollowedStoryUpdateDao.kt` | Create | DB operations for FollowedStoryUpdate |
| `data/model/Article.kt` | Modify | Rename `storyId` → `followedStoryId` |
| `data/dao/ArticleDao.kt` | Modify | Remove storyId queries, add followedStoryId query |
| `data/LumenDatabase.kt` | Modify | Bump version, swap entities/DAOs, remove Story |
| `ml/T5Tokenizer.kt` | Create | Loads tokenizer.json, encodes/decodes T5 token IDs |
| `ml/T5Summarizer.kt` | Create | Runs encoder→decoder ONNX inference, returns summary string |
| `data/NewsRepository.kt` | Modify | Remove StoryMatcher/StoryDao; add follow/unfollow, matching logic |
| `MainActivity.kt` | Modify | Remove StoryMatcher/StoryDao from NewsRepository constructor |
| `ui/ArticleActivity.kt` | Modify | Add FAB, MiniLMEmbedder, T5Summarizer, follow/unfollow |
| `res/layout/activity_article.xml` | Modify | Wrap in CoordinatorLayout, add FAB |
| `StoryAdapter.kt` | Create | RecyclerView adapter for followed story cards |
| `res/layout/item_story_card.xml` | Create | Timeline-style story card layout |
| `fragments/StoryFragment.kt` | Rewrite | Shows list of followed stories |
| `res/layout/fragment_story.xml` | Rewrite | RecyclerView + empty state |
| `ui/StoryDetailActivity.kt` | Create | Full-screen story detail: summary + updates |
| `res/layout/activity_story_detail.xml` | Create | Detail layout |
| `res/layout/item_story_update.xml` | Create | Single update entry layout |
| `data/model/Story.kt` | Delete | — |
| `data/dao/StoryDao.kt` | Delete | — |
| `ml/StoryMatcher.kt` | Delete | — |

---

## Task 1: New Room entities

**Files:**
- Create: `app/src/main/java/com/example/lumen/data/model/FollowedStory.kt`
- Create: `app/src/main/java/com/example/lumen/data/model/FollowedStoryUpdate.kt`

- [ ] **Step 1: Create FollowedStory.kt**

```kotlin
package com.example.lumen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "followed_stories")
data class FollowedStory(
    @PrimaryKey val id: String,
    val title: String,
    val keywords: String,           // comma-separated, for keyword pre-filter
    val centroidEmbedding: String,  // JSON float[384] from MiniLM
    val summary: String,            // T5 initial summary
    val articleUrls: String,        // comma-separated URLs at follow time
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val updateCount: Int = 0
)
```

- [ ] **Step 2: Create FollowedStoryUpdate.kt**

```kotlin
package com.example.lumen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "followed_story_updates")
data class FollowedStoryUpdate(
    @PrimaryKey val id: String,
    val storyId: String,        // FK → FollowedStory.id
    val updateText: String,     // T5 summary of new articles only
    val newArticleUrls: String, // comma-separated new article URLs
    val createdAt: Long
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/model/FollowedStory.kt
git add app/src/main/java/com/example/lumen/data/model/FollowedStoryUpdate.kt
git commit -m "feat: add FollowedStory and FollowedStoryUpdate entities"
```

---

## Task 2: New DAOs

**Files:**
- Create: `app/src/main/java/com/example/lumen/data/dao/FollowedStoryDao.kt`
- Create: `app/src/main/java/com/example/lumen/data/dao/FollowedStoryUpdateDao.kt`

- [ ] **Step 1: Create FollowedStoryDao.kt**

```kotlin
package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.FollowedStory
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedStoryDao {

    @Query("SELECT * FROM followed_stories ORDER BY lastUpdatedAt DESC")
    fun getAllStories(): Flow<List<FollowedStory>>

    @Query("SELECT * FROM followed_stories ORDER BY lastUpdatedAt DESC")
    suspend fun getAllStoriesSnapshot(): List<FollowedStory>

    @Query("SELECT * FROM followed_stories WHERE id = :id LIMIT 1")
    suspend fun getStoryById(id: String): FollowedStory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(story: FollowedStory)

    @Query("DELETE FROM followed_stories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE followed_stories SET lastUpdatedAt = :lastUpdatedAt, updateCount = :updateCount WHERE id = :id")
    suspend fun updateLastSeen(id: String, lastUpdatedAt: Long, updateCount: Int)
}
```

- [ ] **Step 2: Create FollowedStoryUpdateDao.kt**

```kotlin
package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.FollowedStoryUpdate
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedStoryUpdateDao {

    @Query("SELECT * FROM followed_story_updates WHERE storyId = :storyId ORDER BY createdAt ASC")
    fun getUpdatesForStory(storyId: String): Flow<List<FollowedStoryUpdate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(update: FollowedStoryUpdate)

    @Query("DELETE FROM followed_story_updates WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/dao/FollowedStoryDao.kt
git add app/src/main/java/com/example/lumen/data/dao/FollowedStoryUpdateDao.kt
git commit -m "feat: add FollowedStoryDao and FollowedStoryUpdateDao"
```

---

## Task 3: Update Article entity and ArticleDao

**Files:**
- Modify: `app/src/main/java/com/example/lumen/data/model/Article.kt`
- Modify: `app/src/main/java/com/example/lumen/data/dao/ArticleDao.kt`

- [ ] **Step 1: Replace `storyId` with `followedStoryId` in Article.kt**

Replace the `// Story tracking` block:
```kotlin
    // Story tracking
    val storyId: String? = null,
```
with:
```kotlin
    // Followed story reference
    val followedStoryId: String? = null,
```

- [ ] **Step 2: Update ArticleDao.kt — remove storyId queries, add followedStoryId query**

Remove these two methods (they reference `storyId`):
```kotlin
@Query("SELECT * FROM articles WHERE storyId = :storyId ORDER BY publishedAt ASC")
fun getStory(storyId: String): Flow<List<Article>>

@Query("SELECT * FROM articles GROUP BY storyId ORDER BY publishedAt DESC")
fun getAllStories(): Flow<List<Article>>
```

Also remove the duplicate `getArticlesByTopic(topic: String)` (without `since` param, line 12) — it's superseded by the two-param version.

Add this new method:
```kotlin
@Query("UPDATE articles SET followedStoryId = :storyId WHERE url = :url")
suspend fun setFollowedStoryId(url: String, storyId: String?)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/model/Article.kt
git add app/src/main/java/com/example/lumen/data/dao/ArticleDao.kt
git commit -m "feat: rename storyId to followedStoryId in Article and ArticleDao"
```

---

## Task 4: Update LumenDatabase

**Files:**
- Modify: `app/src/main/java/com/example/lumen/data/LumenDatabase.kt`

- [ ] **Step 1: Rewrite LumenDatabase.kt**

```kotlin
package com.example.lumen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.lumen.data.dao.ArticleDao
import com.example.lumen.data.dao.FollowedStoryDao
import com.example.lumen.data.dao.FollowedStoryUpdateDao
import com.example.lumen.data.dao.UserPrefsDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.FollowedStory
import com.example.lumen.data.model.FollowedStoryUpdate
import com.example.lumen.data.model.UserPrefs

@Database(
    entities = [Article::class, UserPrefs::class, FollowedStory::class, FollowedStoryUpdate::class],
    version = 2,
    exportSchema = false
)
abstract class LumenDatabase : RoomDatabase() {

    abstract fun articleDao(): ArticleDao
    abstract fun userPrefsDao(): UserPrefsDao
    abstract fun followedStoryDao(): FollowedStoryDao
    abstract fun followedStoryUpdateDao(): FollowedStoryUpdateDao

    companion object {
        @Volatile
        private var INSTANCE: LumenDatabase? = null

        fun getInstance(context: Context): LumenDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LumenDatabase::class.java,
                    "lumen_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/LumenDatabase.kt
git commit -m "feat: update LumenDatabase v2 with FollowedStory tables"
```

---

## Task 5: T5Tokenizer

**Files:**
- Create: `app/src/main/java/com/example/lumen/ml/T5Tokenizer.kt`

The T5 tokenizer uses SentencePiece Unigram. `tokenizer.json` (HuggingFace fast tokenizer format) contains the full vocab as `[[piece, log_score], ...]` under `model.vocab`, where the array index is the token ID. Pieces use `▁` (U+2581) as a word-start marker.

- [ ] **Step 1: Create T5Tokenizer.kt**

```kotlin
package com.example.lumen.ml

import android.content.Context
import org.json.JSONObject

class T5Tokenizer(context: Context) {

    private val idToPiece: Array<String>
    private val pieceToId: Map<String, Int>

    companion object {
        const val PAD_ID = 0L
        const val EOS_ID = 1L
        const val UNK_ID = 2L
        private const val SPACE_MARKER = '▁' // ▁
    }

    init {
        val json = context.assets.open("t5/tokenizer.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val vocabArray = root.getJSONObject("model").getJSONArray("vocab")
        val size = vocabArray.length()
        idToPiece = Array(size) { i ->
            vocabArray.getJSONArray(i).getString(0)
        }
        pieceToId = HashMap<String, Int>(size).also { map ->
            for (i in 0 until size) map[idToPiece[i]] = i
        }
    }

    fun encode(text: String, maxLength: Int = 512): Pair<LongArray, LongArray> {
        val words = text.trim().split(Regex("\\s+"))
        val ids = mutableListOf<Long>()

        for ((wi, word) in words.withIndex()) {
            val marked = if (wi == 0) word else "$SPACE_MARKER$word"
            var pos = 0
            while (pos < marked.length && ids.size < maxLength - 1) {
                var matched = false
                for (end in marked.length downTo pos + 1) {
                    val sub = marked.substring(pos, end)
                    val id = pieceToId[sub]
                    if (id != null) {
                        ids.add(id.toLong())
                        pos = end
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    ids.add(UNK_ID)
                    pos++
                }
            }
        }
        ids.add(EOS_ID) // T5 appends EOS

        val padded = LongArray(maxLength) { PAD_ID }
        val mask = LongArray(maxLength) { 0L }
        val len = minOf(ids.size, maxLength)
        for (i in 0 until len) {
            padded[i] = ids[i]
            mask[i] = 1L
        }
        return Pair(padded, mask)
    }

    fun decode(ids: LongArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID) break
            if (id < 0 || id >= idToPiece.size) continue
            val piece = idToPiece[id.toInt()]
            if (piece.startsWith(SPACE_MARKER)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(piece.substring(1))
            } else {
                sb.append(piece)
            }
        }
        return sb.toString().trim()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lumen/ml/T5Tokenizer.kt
git commit -m "feat: add T5Tokenizer loading SentencePiece vocab from tokenizer.json"
```

---

## Task 6: T5Summarizer

**Files:**
- Create: `app/src/main/java/com/example/lumen/ml/T5Summarizer.kt`

T5 inference: encoder takes `(input_ids, attention_mask)` → `last_hidden_state`. Decoder takes `(input_ids, encoder_hidden_states, encoder_attention_mask)` → `logits` over vocab. Greedy decode: argmax of last position logits until EOS or max tokens.

- [ ] **Step 1: Create T5Summarizer.kt**

```kotlin
package com.example.lumen.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.lumen.data.model.Article
import java.nio.LongBuffer

class T5Summarizer(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val tokenizer = T5Tokenizer(context)

    companion object {
        private const val MAX_INPUT_TOKENS = 512
        private const val MAX_OUTPUT_TOKENS = 150
        private const val TAG = "T5Summarizer"
    }

    init {
        val encBytes = context.assets.open("t5/encoder_model.onnx").readBytes()
        encoderSession = env.createSession(encBytes, OrtSession.SessionOptions())
        val decBytes = context.assets.open("t5/decoder_model.onnx").readBytes()
        decoderSession = env.createSession(decBytes, OrtSession.SessionOptions())
    }

    @Synchronized
    fun summarize(articles: List<Article>): String {
        return try {
            val inputText = buildInputText(articles)
            val (inputIds, attMask) = tokenizer.encode(inputText, MAX_INPUT_TOKENS)
            val seqLen = inputIds.size.toLong()

            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen))
            val attMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val encoderOutputs = encoderSession.run(
                mapOf("input_ids" to inputIdsTensor, "attention_mask" to attMaskTensor)
            )
            val encoderHidden = encoderOutputs[0].value // float[1, seqLen, 512]

            inputIdsTensor.close()
            attMaskTensor.close()

            val encoderHiddenTensor = OnnxTensor.createTensor(env, encoderHidden as Array<*>)
            val encoderMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val generatedIds = mutableListOf(T5Tokenizer.PAD_ID)

            for (step in 0 until MAX_OUTPUT_TOKENS) {
                val decIds = generatedIds.toLongArray()
                val decLen = decIds.size.toLong()
                val decIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), longArrayOf(1, decLen))

                val decInputs = mapOf(
                    "input_ids" to decIdsTensor,
                    "encoder_hidden_states" to encoderHiddenTensor,
                    "encoder_attention_mask" to encoderMaskTensor
                )

                val decOutputs = decoderSession.run(decInputs)
                val logits = decOutputs[0].value as Array<*> // [1, decLen, vocabSize]
                val lastLogits = ((logits[0] as Array<*>)[decLen.toInt() - 1]) as FloatArray
                val nextId = lastLogits.indices.maxByOrNull { lastLogits[it] }?.toLong() ?: T5Tokenizer.EOS_ID

                decIdsTensor.close()
                decOutputs.close()

                if (nextId == T5Tokenizer.EOS_ID) break
                generatedIds.add(nextId)
            }

            encoderHiddenTensor.close()
            encoderMaskTensor.close()
            encoderOutputs.close()

            tokenizer.decode(generatedIds.toLongArray())
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed: ${e.message}")
            "Summary unavailable."
        }
    }

    private fun buildInputText(articles: List<Article>): String {
        val parts = articles.map { article ->
            val bodyPreview = article.title // body not available here; caller may enrich
            "summarize: ${article.title}"
        }
        return parts.joinToString(" | ")
    }

    fun summarizeWithBodies(articles: List<Article>, bodies: Map<String, String>): String {
        val parts = articles.map { article ->
            val body = bodies[article.url]?.take(300) ?: ""
            "summarize: ${article.title}. $body"
        }
        return try {
            val inputText = parts.joinToString(" | ")
            val (inputIds, attMask) = tokenizer.encode(inputText, MAX_INPUT_TOKENS)
            val seqLen = inputIds.size.toLong()

            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen))
            val attMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val encoderOutputs = encoderSession.run(
                mapOf("input_ids" to inputIdsTensor, "attention_mask" to attMaskTensor)
            )
            val encoderHidden = encoderOutputs[0].value

            inputIdsTensor.close()
            attMaskTensor.close()

            val encoderHiddenTensor = OnnxTensor.createTensor(env, encoderHidden as Array<*>)
            val encoderMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val generatedIds = mutableListOf(T5Tokenizer.PAD_ID)

            for (step in 0 until MAX_OUTPUT_TOKENS) {
                val decIds = generatedIds.toLongArray()
                val decLen = decIds.size.toLong()
                val decIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), longArrayOf(1, decLen))

                val decInputs = mapOf(
                    "input_ids" to decIdsTensor,
                    "encoder_hidden_states" to encoderHiddenTensor,
                    "encoder_attention_mask" to encoderMaskTensor
                )

                val decOutputs = decoderSession.run(decInputs)
                val logits = decOutputs[0].value as Array<*>
                val lastLogits = ((logits[0] as Array<*>)[decLen.toInt() - 1]) as FloatArray
                val nextId = lastLogits.indices.maxByOrNull { lastLogits[it] }?.toLong() ?: T5Tokenizer.EOS_ID

                decIdsTensor.close()
                decOutputs.close()

                if (nextId == T5Tokenizer.EOS_ID) break
                generatedIds.add(nextId)
            }

            encoderHiddenTensor.close()
            encoderMaskTensor.close()
            encoderOutputs.close()

            tokenizer.decode(generatedIds.toLongArray())
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed: ${e.message}")
            "Summary unavailable."
        }
    }

    fun close() {
        encoderSession.close()
        decoderSession.close()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lumen/ml/T5Summarizer.kt
git commit -m "feat: add T5Summarizer with encoder-decoder ONNX inference"
```

---

## Task 7: Update NewsRepository

**Files:**
- Modify: `app/src/main/java/com/example/lumen/data/NewsRepository.kt`

Remove `StoryDao` and `StoryMatcher`. Add `FollowedStoryDao`, `FollowedStoryUpdateDao`, `MiniLMEmbedder` (lazy via Context). Add follow/unfollow/matching logic.

- [ ] **Step 1: Rewrite NewsRepository.kt**

```kotlin
package com.example.lumen.data

import android.content.Context
import android.util.Log
import com.example.lumen.data.dao.ArticleDao
import com.example.lumen.data.dao.FollowedStoryDao
import com.example.lumen.data.dao.FollowedStoryUpdateDao
import com.example.lumen.data.dao.UserPrefsDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.ArticleCluster
import com.example.lumen.data.model.FollowedStory
import com.example.lumen.data.model.FollowedStoryUpdate
import com.example.lumen.data.model.UserPrefs
import com.example.lumen.ml.MiniLMEmbedder
import com.example.lumen.ml.T5Summarizer
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.network.ArticleMapper
import com.example.lumen.network.RetrofitClient
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.UUID
import kotlin.math.sqrt

class NewsRepository(
    private val articleDao: ArticleDao,
    private val followedStoryDao: FollowedStoryDao,
    private val followedStoryUpdateDao: FollowedStoryUpdateDao,
    private val userPrefsDao: UserPrefsDao,
    private val articleFetcher: ArticleFetcher,
    private val context: Context
) {
    private val embedder: MiniLMEmbedder by lazy { MiniLMEmbedder(context) }
    private val t5: T5Summarizer by lazy { T5Summarizer(context) }

    // Feed
    fun getAllArticles(topic: String? = null): Flow<List<Article>> {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3_600_000
        return if (topic == null) {
            articleDao.getAllArticles(sevenDaysAgo)
        } else {
            articleDao.getArticlesByTopic(sevenDaysAgo, topic)
        }
    }

    suspend fun saveArticles(articles: List<Article>) {
        articles.forEach { article ->
            articleDao.insert(article)
        }
    }

    suspend fun markAsRead(url: String) {
        articleDao.markAsRead(url)
    }

    suspend fun isCacheFresh(): Boolean {
        val oneHourAgo = System.currentTimeMillis() - 3_600_000
        return articleDao.getArticlesSince(oneHourAgo).isNotEmpty()
    }

    // Prefs
    suspend fun getPrefs(): UserPrefs? = userPrefsDao.getPrefs()
    suspend fun savePrefs(prefs: UserPrefs) = userPrefsDao.savePrefs(prefs)

    // Stats
    suspend fun getReadCountThisWeek(): Int {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 3_600_000
        return articleDao.countReadSince(sevenDaysAgo)
    }

    suspend fun getAverageBias(): Float = articleDao.getAverageBias()

    suspend fun pruneOldArticles() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 3_600_000
        articleDao.deleteOlderThan(thirtyDaysAgo)
    }

    // Followed stories
    fun getFollowedStories(): Flow<List<FollowedStory>> = followedStoryDao.getAllStories()

    fun getStoryUpdates(storyId: String): Flow<List<FollowedStoryUpdate>> =
        followedStoryUpdateDao.getUpdatesForStory(storyId)

    suspend fun getStoryById(storyId: String): FollowedStory? =
        followedStoryDao.getStoryById(storyId)

    suspend fun isClusterFollowed(articleUrls: List<String>): String? {
        val stories = followedStoryDao.getAllStoriesSnapshot()
        for (story in stories) {
            val storyUrls = story.articleUrls.split(",").map { it.trim() }
            if (articleUrls.any { it in storyUrls }) return story.id
        }
        return null
    }

    suspend fun followCluster(
        title: String,
        keywords: String,
        centroidEmbedding: FloatArray,
        summary: String,
        articleUrls: List<String>
    ): String {
        val storyId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val story = FollowedStory(
            id = storyId,
            title = title,
            keywords = keywords,
            centroidEmbedding = centroidEmbedding.toJsonString(),
            summary = summary,
            articleUrls = articleUrls.joinToString(","),
            createdAt = now,
            lastUpdatedAt = now,
            updateCount = 0
        )
        followedStoryDao.insert(story)
        articleUrls.forEach { url -> articleDao.setFollowedStoryId(url, storyId) }
        return storyId
    }

    suspend fun unfollowStory(storyId: String) {
        val story = followedStoryDao.getStoryById(storyId) ?: return
        story.articleUrls.split(",").forEach { url ->
            articleDao.setFollowedStoryId(url.trim(), null)
        }
        followedStoryUpdateDao.deleteForStory(storyId)
        followedStoryDao.delete(storyId)
    }

    private val GUARDIAN_API_KEY = com.example.lumen.BuildConfig.GUARDIAN_API_KEY
    private val NYT_API_KEY = com.example.lumen.BuildConfig.NYT_API_KEY

    suspend fun fetchAndSaveArticles() {
        val fromDate = getSevenDaysAgoFormatted()
        val guardianArticles = fetchFromGuardian(fromDate)
        val nytArticles = fetchFromNyt()
        val allArticles = (guardianArticles + nytArticles).distinctBy { it.url }
        saveArticles(allArticles)
        matchNewArticlesToStories(allArticles)
    }

    private suspend fun matchNewArticlesToStories(newArticles: List<Article>) {
        val stories = followedStoryDao.getAllStoriesSnapshot()
        if (stories.isEmpty()) return

        val storyNewArticles = mutableMapOf<String, MutableList<Article>>()

        for (article in newArticles) {
            val titleLower = article.title.lowercase()

            for (story in stories) {
                val storyUrls = story.articleUrls.split(",").map { it.trim() }
                if (article.url in storyUrls) continue // already in this story

                // keyword pre-filter
                val keywords = story.keywords.split(",").map { it.trim().lowercase() }
                val keywordHits = keywords.count { titleLower.contains(it) }
                if (keywordHits < 2) continue

                // MiniLM similarity check
                val articleEmbedding = embedder.embed(article.title) ?: continue
                val centroid = story.centroidEmbedding.toFloatArray()
                val similarity = embedder.similarity(articleEmbedding, centroid)
                if (similarity < 0.50f) continue

                articleDao.setFollowedStoryId(article.url, story.id)
                storyNewArticles.getOrPut(story.id) { mutableListOf() }.add(article)
            }
        }

        for ((storyId, matchedArticles) in storyNewArticles) {
            val story = followedStoryDao.getStoryById(storyId) ?: continue
            val bodies = mutableMapOf<String, String>()
            for (article in matchedArticles) {
                val body = articleFetcher.fetchBody(article.url, article.title)
                if (!body.isNullOrBlank()) bodies[article.url] = body
            }
            val updateText = t5.summarizeWithBodies(matchedArticles, bodies)
            val update = FollowedStoryUpdate(
                id = UUID.randomUUID().toString(),
                storyId = storyId,
                updateText = updateText,
                newArticleUrls = matchedArticles.joinToString(",") { it.url },
                createdAt = System.currentTimeMillis()
            )
            followedStoryUpdateDao.insert(update)
            followedStoryDao.updateLastSeen(
                id = storyId,
                lastUpdatedAt = System.currentTimeMillis(),
                updateCount = story.updateCount + 1
            )
        }
    }

    private suspend fun fetchFromGuardian(fromDate: String): List<Article> {
        return try {
            RetrofitClient.guardianApi.getArticles(
                apiKey = GUARDIAN_API_KEY,
                fromDate = fromDate
            ).response.results.map { ArticleMapper.fromGuardian(it) }
        } catch (e: Exception) {
            Log.e("Lumen", "Guardian fetch failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchFromNyt(): List<Article> {
        return try {
            val beginDate = getSevenDaysAgoNytFormat()
            RetrofitClient.nytApi.getRecentArticles(
                apiKey = NYT_API_KEY,
                beginDate = beginDate
            ).response.docs?.map { ArticleMapper.fromNyt(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("Lumen", "NYT fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun getSevenDaysAgoFormatted(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 3_600_000))
    }

    private fun getSevenDaysAgoNytFormat(): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 3_600_000))
    }
}

// Extension helpers for FloatArray ↔ JSON string
fun FloatArray.toJsonString(): String = JSONArray(this.toTypedArray()).toString()
fun String.toFloatArray(): FloatArray {
    val arr = JSONArray(this)
    return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lumen/data/NewsRepository.kt
git commit -m "feat: overhaul NewsRepository with follow/unfollow and article matching"
```

---

## Task 8: Update MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/lumen/MainActivity.kt`

Remove `StoryMatcher` and `StoryDao` from `NewsRepository` constructor. Add `context` param.

- [ ] **Step 1: Replace repository instantiation in MainActivity.kt**

Replace:
```kotlin
import com.example.lumen.ml.StoryMatcher
...
repository = NewsRepository(
    database.articleDao(),
    database.storyDao(),
    database.userPrefsDao(),
    ArticleFetcher(),
    StoryMatcher(database.storyDao())
)
```
with:
```kotlin
repository = NewsRepository(
    database.articleDao(),
    database.followedStoryDao(),
    database.followedStoryUpdateDao(),
    database.userPrefsDao(),
    ArticleFetcher(),
    this
)
```

Also remove the `import com.example.lumen.ml.StoryMatcher` line.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lumen/MainActivity.kt
git commit -m "feat: update MainActivity to use new NewsRepository signature"
```

---

## Task 9: ArticleActivity — FAB and follow logic

**Files:**
- Modify: `app/src/main/res/layout/activity_article.xml`
- Modify: `app/src/main/java/com/example/lumen/ui/ArticleActivity.kt`

### Part A: Layout

- [ ] **Step 1: Wrap activity_article.xml root in CoordinatorLayout and add FAB**

Replace the opening `<LinearLayout` root tag with `<androidx.coordinatorlayout.widget.CoordinatorLayout` and close it at the end. Keep all existing inner content. Add the FAB as a direct child of `CoordinatorLayout` after the inner `LinearLayout`:

The file should look like:
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/dark_blue">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:background="@color/dark_blue">

        <!-- PASTE ALL EXISTING CONTENT (Toolbar, HorizontalScrollView, ScrollView) HERE unchanged -->

    </LinearLayout>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab_follow"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:contentDescription="Follow story"
        app:srcCompat="@drawable/ic_bookmark_border"
        app:backgroundTint="@color/yellow"
        app:tint="@color/dark_blue"/>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

> **Note:** You need a bookmark drawable. If `ic_bookmark_border` doesn't exist, add it via Android Studio → right-click `res/drawable` → New → Vector Asset → search "bookmark border". Also add `ic_bookmark` (filled) for the followed state.

### Part B: Kotlin

- [ ] **Step 2: Add follow state fields and imports to ArticleActivity.kt**

Add at the top of the class (after existing field declarations):
```kotlin
import com.example.lumen.data.NewsRepository
import com.example.lumen.ml.MiniLMEmbedder
import com.example.lumen.ml.T5Summarizer
import com.example.lumen.data.toJsonString
import com.example.lumen.network.ArticleFetcher
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
...

private lateinit var fabFollow: FloatingActionButton
private val embedder: MiniLMEmbedder by lazy { MiniLMEmbedder(this) }
private val t5Summarizer: T5Summarizer by lazy { T5Summarizer(this) }
private var followedStoryId: String? = null
private lateinit var repository: NewsRepository
```

- [ ] **Step 3: Initialize repository and FAB in onCreate, after `loadArticlesAndDisplay` call**

Inside `onCreate`, after `setContentView`:
```kotlin
val db = LumenDatabase.getInstance(this)
repository = NewsRepository(
    db.articleDao(),
    db.followedStoryDao(),
    db.followedStoryUpdateDao(),
    db.userPrefsDao(),
    ArticleFetcher(),
    this
)

fabFollow = findViewById(R.id.fab_follow)
fabFollow.setOnClickListener { handleFollowTap() }
```

- [ ] **Step 4: Add checkFollowState() called inside loadArticlesAndDisplay after displayArticle**

Inside `loadArticlesAndDisplay`, after `displayArticle(currentIndex)`:
```kotlin
checkFollowState()
```

Add the method:
```kotlin
private fun checkFollowState() {
    lifecycleScope.launch {
        val urls = articles.map { it.url }
        followedStoryId = withContext(Dispatchers.IO) {
            repository.isClusterFollowed(urls)
        }
        updateFabAppearance()
    }
}

private fun updateFabAppearance() {
    if (followedStoryId != null) {
        fabFollow.setImageResource(R.drawable.ic_bookmark)
    } else {
        fabFollow.setImageResource(R.drawable.ic_bookmark_border)
    }
}
```

- [ ] **Step 5: Add handleFollowTap()**

```kotlin
private fun handleFollowTap() {
    if (followedStoryId != null) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.unfollowStory(followedStoryId!!) }
            followedStoryId = null
            updateFabAppearance()
        }
        return
    }

    fabFollow.isEnabled = false
    lifecycleScope.launch {
        val summary = withContext(Dispatchers.Default) {
            val bodies = mutableMapOf<String, String>()
            for (article in articles) {
                val body = fetcher.fetchBody(article.url, article.title)
                if (!body.isNullOrBlank()) bodies[article.url] = body
            }
            t5Summarizer.summarizeWithBodies(articles, bodies)
        }

        val embeddings = withContext(Dispatchers.Default) {
            articles.mapNotNull { embedder.embed(it.title) }
        }
        val centroid = computeCentroid(embeddings)
        val keywords = extractKeywords(articles.first().title)

        withContext(Dispatchers.IO) {
            followedStoryId = repository.followCluster(
                title = articles.first().title,
                keywords = keywords,
                centroidEmbedding = centroid,
                summary = summary,
                articleUrls = articles.map { it.url }
            )
        }
        fabFollow.isEnabled = true
        updateFabAppearance()
    }
}

private fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
    if (embeddings.isEmpty()) return FloatArray(384)
    val centroid = FloatArray(384)
    for (emb in embeddings) for (i in 0 until 384) centroid[i] += emb[i]
    for (i in 0 until 384) centroid[i] /= embeddings.size
    val mag = sqrt(centroid.sumOf { (it * it).toDouble() }).toFloat()
    return if (mag == 0f) centroid else FloatArray(384) { centroid[it] / mag }
}

private fun extractKeywords(title: String): String {
    val stopWords = setOf("the","a","an","is","in","on","at","to","of","and","or","but","for","with","this","that","its","it")
    return title.lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .split(" ")
        .filter { it.length > 3 && it !in stopWords }
        .take(4)
        .joinToString(",")
}
```

- [ ] **Step 6: Update onDestroy to close new resources**

```kotlin
override fun onDestroy() {
    super.onDestroy()
    biasAnalyzer.close()
    if (::embedder.isInitialized) embedder.close()
    if (::t5Summarizer.isInitialized) t5Summarizer.close()
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/activity_article.xml
git add app/src/main/java/com/example/lumen/ui/ArticleActivity.kt
git commit -m "feat: add follow FAB and follow/unfollow logic to ArticleActivity"
```

---

## Task 10: StoryFragment rewrite + StoryAdapter

**Files:**
- Create: `app/src/main/java/com/example/lumen/StoryAdapter.kt`
- Create: `app/src/main/res/layout/item_story_card.xml`
- Rewrite: `app/src/main/java/com/example/lumen/fragments/StoryFragment.kt`
- Rewrite: `app/src/main/res/layout/fragment_story.xml`

### Layout files

- [ ] **Step 1: Create item_story_card.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="12dp"
    android:layout_marginBottom="10dp"
    app:cardBackgroundColor="#1E3A5F"
    app:cardCornerRadius="10dp"
    app:cardElevation="2dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="14dp">

        <!-- Left: title, sources, summary, footer -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">

            <TextView
                android:id="@+id/tv_story_title"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="14sp"
                android:textStyle="bold"
                android:textColor="#FFFFFF"
                android:layout_marginBottom="6dp"/>

            <LinearLayout
                android:id="@+id/ll_source_badges"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginBottom="8dp"/>

            <TextView
                android:id="@+id/tv_story_summary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="12sp"
                android:textColor="#AAAAAA"
                android:lineSpacingMultiplier="1.4"
                android:maxLines="2"
                android:ellipsize="end"
                android:layout_marginBottom="8dp"/>

            <TextView
                android:id="@+id/tv_story_footer"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="11sp"
                android:textColor="#F5C842"/>

        </LinearLayout>

        <!-- Right: update dot timeline -->
        <LinearLayout
            android:id="@+id/ll_timeline"
            android:layout_width="16dp"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="center_horizontal"
            android:layout_marginStart="12dp"/>

    </LinearLayout>

</androidx.cardview.widget.CardView>
```

- [ ] **Step 2: Create fragment_story.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/dark_blue">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_stories"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:paddingTop="12dp"
        android:clipToPadding="false"/>

    <TextView
        android:id="@+id/tv_empty"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="No followed stories yet.\nOpen an article and tap the bookmark to follow a story."
        android:textColor="#AAAAAA"
        android:textSize="14sp"
        android:gravity="center"
        android:padding="32dp"
        android:visibility="gone"/>

</FrameLayout>
```

### Adapter

- [ ] **Step 3: Create StoryAdapter.kt**

```kotlin
package com.example.lumen

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.data.model.FollowedStory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoryAdapter(
    private val onStoryClick: (FollowedStory) -> Unit
) : ListAdapter<FollowedStory, StoryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FollowedStory>() {
            override fun areItemsTheSame(a: FollowedStory, b: FollowedStory) = a.id == b.id
            override fun areContentsTheSame(a: FollowedStory, b: FollowedStory) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_story_title)
        val llSources: LinearLayout = view.findViewById(R.id.ll_source_badges)
        val tvSummary: TextView = view.findViewById(R.id.tv_story_summary)
        val tvFooter: TextView = view.findViewById(R.id.tv_story_footer)
        val llTimeline: LinearLayout = view.findViewById(R.id.ll_timeline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_story_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val story = getItem(position)
        holder.tvTitle.text = story.title
        holder.tvSummary.text = story.summary

        // source badges from article URLs (extract source from URL heuristic)
        holder.llSources.removeAllViews()
        val sources = inferSources(story.articleUrls)
        sources.forEach { source ->
            val badge = TextView(holder.itemView.context).apply {
                text = source
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(16, 4, 16, 4)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(sourceColor(source))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
            }
            holder.llSources.addView(badge)
        }

        // footer
        val updateText = if (story.updateCount == 0) "No updates yet" else "${story.updateCount} update${if (story.updateCount > 1) "s" else ""}"
        val dateText = formatDate(story.lastUpdatedAt)
        holder.tvFooter.text = "$updateText · $dateText"

        // dot timeline
        holder.llTimeline.removeAllViews()
        val totalDots = maxOf(1, story.updateCount + 1)
        for (i in 0 until totalDots) {
            val alpha = when {
                i == 0 -> 255
                i == 1 -> 200
                else -> 100
            }
            val dot = View(holder.itemView.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(alpha, 245, 200, 66))
                }
                layoutParams = LinearLayout.LayoutParams(20, 20).apply { bottomMargin = 6 }
            }
            holder.llTimeline.addView(dot)

            if (i < totalDots - 1) {
                val line = View(holder.itemView.context).apply {
                    setBackgroundColor(Color.argb(60, 245, 200, 66))
                    layoutParams = LinearLayout.LayoutParams(4, 24).apply {
                        leftMargin = 8
                        bottomMargin = 6
                    }
                }
                holder.llTimeline.addView(line)
            }
        }

        holder.itemView.setOnClickListener { onStoryClick(story) }
    }

    private fun inferSources(articleUrls: String): List<String> {
        val urls = articleUrls.split(",").map { it.trim() }
        return urls.mapNotNull { url ->
            when {
                url.contains("theguardian") -> "Guardian"
                url.contains("nytimes") -> "NYT"
                else -> null
            }
        }.distinct()
    }

    private fun sourceColor(source: String): Int = when (source) {
        "Guardian" -> Color.parseColor("#005689")
        "NYT" -> Color.parseColor("#000000")
        else -> Color.parseColor("#666666")
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
```

### Fragment

- [ ] **Step 4: Rewrite StoryFragment.kt**

```kotlin
package com.example.lumen.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.MainActivity
import com.example.lumen.R
import com.example.lumen.StoryAdapter
import com.example.lumen.ui.StoryDetailActivity
import kotlinx.coroutines.launch

class StoryFragment : Fragment() {

    private lateinit var adapter: StoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_story, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvEmpty = view.findViewById(R.id.tv_empty)

        adapter = StoryAdapter { story ->
            val intent = Intent(requireContext(), StoryDetailActivity::class.java)
            intent.putExtra(StoryDetailActivity.EXTRA_STORY_ID, story.id)
            startActivity(intent)
        }

        view.findViewById<RecyclerView>(R.id.rv_stories).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StoryFragment.adapter
        }

        val repository = (requireActivity() as MainActivity).repository
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getFollowedStories().collect { stories ->
                adapter.submitList(stories)
                tvEmpty.visibility = if (stories.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/item_story_card.xml
git add app/src/main/res/layout/fragment_story.xml
git add app/src/main/java/com/example/lumen/StoryAdapter.kt
git add app/src/main/java/com/example/lumen/fragments/StoryFragment.kt
git commit -m "feat: implement StoryFragment and StoryAdapter with timeline cards"
```

---

## Task 11: StoryDetailActivity

**Files:**
- Create: `app/src/main/res/layout/activity_story_detail.xml`
- Create: `app/src/main/res/layout/item_story_update.xml`
- Create: `app/src/main/java/com/example/lumen/ui/StoryDetailActivity.kt`

- [ ] **Step 1: Create item_story_update.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingBottom="16dp"
    android:paddingEnd="16dp">

    <View
        android:layout_width="3dp"
        android:layout_height="match_parent"
        android:layout_marginEnd="12dp"
        android:background="#F5C84299"/>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tv_update_date"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="10sp"
            android:textColor="#888888"
            android:layout_marginBottom="4dp"/>

        <TextView
            android:id="@+id/tv_update_text"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="#DDDDDD"
            android:lineSpacingMultiplier="1.5"/>

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 2: Create activity_story_detail.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/dark_blue">

    <androidx.appcompat.widget.Toolbar
        android:id="@+id/story_detail_toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/dark_blue">

        <ImageButton
            android:id="@+id/btn_back"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:src="@drawable/ic_arrow_back"
            android:background="?attr/selectableItemBackgroundBorderless"/>

    </androidx.appcompat.widget.Toolbar>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/yellow"/>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <TextView
                android:id="@+id/tv_detail_title"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="20sp"
                android:textStyle="bold"
                android:textColor="#FFFFFF"
                android:layout_marginBottom="10dp"/>

            <LinearLayout
                android:id="@+id/ll_detail_sources"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginBottom="6dp"/>

            <TextView
                android:id="@+id/tv_detail_followed_date"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="11sp"
                android:textColor="#888888"
                android:layout_marginBottom="16dp"/>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="#ffffff22"
                android:layout_marginBottom="14dp"/>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="SUMMARY"
                android:textColor="#AAAAAA"
                android:textSize="10sp"
                android:letterSpacing="0.12"
                android:layout_marginBottom="8dp"/>

            <TextView
                android:id="@+id/tv_detail_summary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="14sp"
                android:textColor="#DDDDDD"
                android:lineSpacingMultiplier="1.6"
                android:layout_marginBottom="24dp"/>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="#ffffff22"
                android:layout_marginBottom="14dp"/>

            <TextView
                android:id="@+id/tv_updates_label"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="UPDATES"
                android:textColor="#AAAAAA"
                android:textSize="10sp"
                android:letterSpacing="0.12"
                android:layout_marginBottom="12dp"/>

            <LinearLayout
                android:id="@+id/ll_updates_container"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"/>

            <TextView
                android:id="@+id/tv_no_updates"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="No updates yet."
                android:textColor="#666666"
                android:textSize="12sp"
                android:visibility="gone"/>

        </LinearLayout>

    </ScrollView>

</LinearLayout>
```

- [ ] **Step 3: Create StoryDetailActivity.kt**

```kotlin
package com.example.lumen.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.data.model.FollowedStory
import com.example.lumen.data.model.FollowedStoryUpdate
import com.example.lumen.network.ArticleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STORY_ID = "extra_story_id"
    }

    private lateinit var tvTitle: TextView
    private lateinit var llSources: LinearLayout
    private lateinit var tvFollowedDate: TextView
    private lateinit var tvSummary: TextView
    private lateinit var llUpdatesContainer: LinearLayout
    private lateinit var tvNoUpdates: TextView
    private lateinit var repository: NewsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_detail)

        tvTitle = findViewById(R.id.tv_detail_title)
        llSources = findViewById(R.id.ll_detail_sources)
        tvFollowedDate = findViewById(R.id.tv_detail_followed_date)
        tvSummary = findViewById(R.id.tv_detail_summary)
        llUpdatesContainer = findViewById(R.id.ll_updates_container)
        tvNoUpdates = findViewById(R.id.tv_no_updates)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val db = LumenDatabase.getInstance(this)
        repository = NewsRepository(
            db.articleDao(),
            db.followedStoryDao(),
            db.followedStoryUpdateDao(),
            db.userPrefsDao(),
            ArticleFetcher(),
            this
        )

        val storyId = intent.getStringExtra(EXTRA_STORY_ID) ?: return finish()

        lifecycleScope.launch {
            val story = withContext(Dispatchers.IO) { repository.getStoryById(storyId) } ?: return@launch finish()
            bindStory(story)

            repository.getStoryUpdates(storyId).collect { updates ->
                bindUpdates(updates)
            }
        }
    }

    private fun bindStory(story: FollowedStory) {
        tvTitle.text = story.title
        tvSummary.text = story.summary
        tvFollowedDate.text = "Followed ${formatDate(story.createdAt)}"

        llSources.removeAllViews()
        val sources = story.articleUrls.split(",").mapNotNull { url ->
            when {
                url.contains("theguardian") -> "Guardian"
                url.contains("nytimes") -> "NYT"
                else -> null
            }
        }.distinct()
        sources.forEach { source ->
            val badge = TextView(this).apply {
                text = source
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(16, 4, 16, 4)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(if (source == "Guardian") Color.parseColor("#005689") else Color.parseColor("#000000"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
            }
            llSources.addView(badge)
        }
    }

    private fun bindUpdates(updates: List<FollowedStoryUpdate>) {
        llUpdatesContainer.removeAllViews()
        if (updates.isEmpty()) {
            tvNoUpdates.visibility = View.VISIBLE
            return
        }
        tvNoUpdates.visibility = View.GONE
        for (update in updates) {
            val updateView = LayoutInflater.from(this)
                .inflate(R.layout.item_story_update, llUpdatesContainer, false)
            updateView.findViewById<TextView>(R.id.tv_update_date).text = formatDate(update.createdAt)
            updateView.findViewById<TextView>(R.id.tv_update_text).text = update.updateText
            llUpdatesContainer.addView(updateView)
        }
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
```

- [ ] **Step 4: Register StoryDetailActivity in AndroidManifest.xml**

Open `app/src/main/AndroidManifest.xml` and add inside `<application>`:
```xml
<activity android:name=".ui.StoryDetailActivity"/>
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_story_detail.xml
git add app/src/main/res/layout/item_story_update.xml
git add app/src/main/java/com/example/lumen/ui/StoryDetailActivity.kt
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add StoryDetailActivity with summary and update timeline"
```

---

## Task 12: Delete old files and build

**Files:**
- Delete: `app/src/main/java/com/example/lumen/data/model/Story.kt`
- Delete: `app/src/main/java/com/example/lumen/data/dao/StoryDao.kt`
- Delete: `app/src/main/java/com/example/lumen/ml/StoryMatcher.kt`

- [ ] **Step 1: Delete old files**

```bash
rm app/src/main/java/com/example/lumen/data/model/Story.kt
rm app/src/main/java/com/example/lumen/data/dao/StoryDao.kt
rm app/src/main/java/com/example/lumen/ml/StoryMatcher.kt
```

- [ ] **Step 2: Build and fix any compile errors**

```bash
gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If compile errors appear, they will be import references to `Story`, `StoryDao`, or `StoryMatcher` — fix each by removing or updating the import/usage.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: remove Story, StoryDao, StoryMatcher — replaced by FollowedStory system"
```

---

## Task 13: Smoke test on device

- [ ] **Step 1: Install on device/emulator**

```bash
gradlew.bat installDebug
```

- [ ] **Step 2: Manual verification checklist**

1. App opens → Feed loads articles ✓
2. Tap a cluster → ArticleActivity opens, FAB visible (bookmark outline) ✓
3. Tap FAB → FAB shows loading (disabled briefly) → FAB turns filled ✓
4. Go back → tap Stories tab → story card appears with title, sources, summary ✓
5. Tap story card → StoryDetailActivity opens with summary ✓
6. Unfollow: go back to same article cluster, FAB is filled → tap → FAB turns outline ✓
7. Stories tab → story removed ✓

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: complete followed stories feature"
```
