package com.example.lumen.data

import android.content.Context
import android.util.Log
import com.example.lumen.data.dao.ArticleDao
import com.example.lumen.data.dao.FollowedStoryDao
import com.example.lumen.data.dao.FollowedStoryUpdateDao
import com.example.lumen.data.dao.UserPrefsDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.FollowedStory
import com.example.lumen.data.model.FollowedStoryUpdate
import com.example.lumen.data.model.UserPrefs
import com.example.lumen.data.model.DashboardStats
import com.example.lumen.data.model.TopicShare
import com.example.lumen.data.model.DayActivity
import com.example.lumen.data.model.SourceShare
import com.example.lumen.ml.MiniLMEmbedder
import com.example.lumen.ml.T5Summarizer
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.network.ArticleMapper
import com.example.lumen.network.RetrofitClient
import com.example.lumen.network.SpiegelRssFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import kotlin.math.roundToInt

class NewsRepository(
    private val articleDao: ArticleDao,
    private val followedStoryDao: FollowedStoryDao,
    private val followedStoryUpdateDao: FollowedStoryUpdateDao,
    private val userPrefsDao: UserPrefsDao,
    private val articleFetcher: ArticleFetcher,
    context: Context
) {
    private val appContext = context.applicationContext
    private val embedder: MiniLMEmbedder by lazy { MiniLMEmbedder(appContext) }
    private val t5: T5Summarizer by lazy { T5Summarizer(appContext) }
    private val spiegelRssFetcher = SpiegelRssFetcher()

    // Feed
    fun getAllArticles(topic: String? = null): Flow<List<Article>> {
        // 14-day window, not 7: the date-bounded Guardian/NYT fetches only ever return
        // articles <7 days old, but RSS sources (Der Spiegel) aren't date-filtered at the
        // network layer and can carry older items — a tighter window dropped them entirely.
        val windowStart = System.currentTimeMillis() - 14L * 24 * 3_600_000
        val allowedLabels = selectedSourceLabels()
        val base = if (topic == null) {
            articleDao.getAllArticles(windowStart)
        } else {
            articleDao.getArticlesByTopic(windowStart, topic)
        }
        // Hide articles from sources the user has since deselected — gating only blocks
        // future fetches, so already-cached rows must be filtered out of the feed too.
        return base.map { list -> list.filter { it.source in allowedLabels } }
    }

    /** The source names the user selected during onboarding, as stored verbatim. */
    private fun selectedSources(): Set<String> =
        appContext
            .getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .getStringSet("sources", emptySet()) ?: emptySet()

    /** Maps selected source names to the `source` labels written on Article rows
     *  (e.g. selection "The New York Times" → article label "New York Times"). */
    private fun selectedSourceLabels(): Set<String> {
        val selected = selectedSources()
        val labels = mutableSetOf<String>()
        if ("The Guardian" in selected) labels.add("The Guardian")
        if ("The New York Times" in selected) labels.add("New York Times")
        if ("Der Spiegel" in selected) labels.add("Der Spiegel")
        return labels
    }

    suspend fun saveArticles(articles: List<Article>) {
        articles.forEach { fetched ->
            // insert() uses REPLACE, and a freshly-fetched Article carries null bias /
            // false read state. Without this merge, every feed refetch would wipe the
            // bias, read state, and follow link already saved on an existing row.
            val existing = articleDao.getByUrl(fetched.url)
            val merged = if (existing != null) {
                fetched.copy(
                    isRead = existing.isRead,
                    readAt = existing.readAt,
                    biasScore = existing.biasScore,
                    biasLabel = existing.biasLabel,
                    biasLeftScore = existing.biasLeftScore,
                    biasCenterScore = existing.biasCenterScore,
                    biasRightScore = existing.biasRightScore,
                    followedStoryId = existing.followedStoryId,
                    clusterId = existing.clusterId
                )
            } else {
                fetched
            }
            articleDao.insert(merged)
        }
    }

    suspend fun markAsRead(url: String) {
        articleDao.markAsRead(url, System.currentTimeMillis())
    }

    /** Persists a computed bias result onto the article row so the Stats screen can aggregate it. */
    suspend fun saveBias(
        url: String, label: String, score: Float, left: Float, center: Float, right: Float
    ) {
        articleDao.updateBiasScores(url, score, label, left, center, right)
    }

    fun getReadArticles(): Flow<List<Article>> = articleDao.getReadArticles()

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

    /** Aggregates the data behind the Stats screen. All figures are scoped to the
     *  past 7 days of *read* articles (the daily heatmap, top sources, etc.). */
    suspend fun getStats(): DashboardStats = withContext(Dispatchers.IO) {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3_600_000

        val articlesRead = articleDao.countReadSinceReadAt(weekAgo)
        val storiesFollowed = followedStoryDao.getAllStoriesSnapshot().size
        val sourcesUsed = articleDao.countDistinctSourcesReadSince(weekAgo)

        val bias = articleDao.getBiasAverages()
        val leftPct = ((bias.avgLeft ?: 0f) * 100).roundToInt()
        val centerPct = ((bias.avgCenter ?: 0f) * 100).roundToInt()
        val rightPct = ((bias.avgRight ?: 0f) * 100).roundToInt()
        val (biasLabel, biasTopPct) = when {
            leftPct == 0 && centerPct == 0 && rightPct == 0 -> "—" to 0
            leftPct >= centerPct && leftPct >= rightPct -> "left" to leftPct
            centerPct >= rightPct -> "center" to centerPct
            else -> "right" to rightPct
        }

        val topicCounts = articleDao.getTopicBreakdownSince(weekAgo)
        val totalTopicReads = topicCounts.sumOf { it.count }
        val topics = topicCounts.take(4).map { tc ->
            val pct = if (totalTopicReads > 0) (tc.count * 100) / totalTopicReads else 0
            TopicShare(tc.topic, pct)
        }

        val dailyActivity = buildDailyActivity(articleDao.getReadTimestampsSince(weekAgo))

        val topSources = articleDao.getTopSourcesSince(weekAgo, 3)
            .map { SourceShare(it.source, it.count) }

        DashboardStats(
            articlesRead = articlesRead,
            storiesFollowed = storiesFollowed,
            sourcesUsed = sourcesUsed,
            biasLabel = biasLabel,
            biasTopPct = biasTopPct,
            topics = topics,
            biasLeftPct = leftPct,
            biasCenterPct = centerPct,
            biasRightPct = rightPct,
            dailyActivity = dailyActivity,
            topSources = topSources
        )
    }

    /** Buckets read timestamps into the last 7 calendar days (oldest → today),
     *  labelling each with its weekday initial. */
    private fun buildDailyActivity(timestamps: List<Long>): List<DayActivity> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val dayMs = 24L * 3_600_000

        val counts = IntArray(7)
        for (ts in timestamps) {
            val daysAgo = kotlin.math.ceil((startOfToday - ts).toDouble() / dayMs).toInt()
            val idx = 6 - daysAgo
            if (idx in 0..6) counts[idx]++
        }

        // Calendar.DAY_OF_WEEK is 1=Sunday .. 7=Saturday.
        val letters = arrayOf("S", "M", "T", "W", "T", "F", "S")
        val result = ArrayList<DayActivity>(7)
        for (i in 0..6) {
            cal.timeInMillis = startOfToday - (6 - i) * dayMs
            result.add(DayActivity(letters[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1], counts[i]))
        }
        return result
    }

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

    /** Resolves a story's article URLs back to Article rows, preserving order. */
    suspend fun getArticlesByUrls(urls: List<String>): List<Article> =
        urls.mapNotNull { articleDao.getByUrl(it.trim()) }

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

    suspend fun fetchAndSaveArticles(topic: String? = null) {
        val fromDate = getSevenDaysAgoFormatted()
        val query = topic ?: ""
        val selectedSources = selectedSources()

        val guardianArticles =
            if ("The Guardian" in selectedSources) fetchFromGuardian(fromDate, query) else emptyList()
        val nytArticles =
            if ("The New York Times" in selectedSources) fetchFromNyt(query) else emptyList()
        val spiegelArticles =
            if ("Der Spiegel" in selectedSources) fetchFromSpiegel() else emptyList()

        var allArticles = (guardianArticles + nytArticles + spiegelArticles).distinctBy { it.url }
        // Tag category fetches with the requested topic so the topic-filtered feed
        // query (getAllArticles(topic)) matches — the API section name rarely equals it.
        if (topic != null) allArticles = allArticles.map { it.copy(topic = topic) }
        saveArticles(allArticles)
        matchNewArticlesToStories(allArticles)
    }

    private suspend fun matchNewArticlesToStories(newArticles: List<Article>) = withContext(Dispatchers.Default) {
        val stories = followedStoryDao.getAllStoriesSnapshot()
        if (stories.isEmpty()) return@withContext

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
                val body = articleFetcher.fetchBody(article)
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

            // Fold the newly matched articles into the story's own source list so they
            // appear in the detail screen's Sources section (and stay reachable), not
            // just as an Updates entry. Also bumps lastUpdatedAt + updateCount.
            val mergedUrls = (story.articleUrls.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
                    matchedArticles.map { it.url }).distinct().joinToString(",")
            followedStoryDao.insert(
                story.copy(
                    articleUrls = mergedUrls,
                    lastUpdatedAt = System.currentTimeMillis(),
                    updateCount = story.updateCount + 1
                )
            )
        }
    }

    private suspend fun fetchFromGuardian(fromDate: String, query: String): List<Article> {
        return try {
            RetrofitClient.guardianApi.getArticles(
                apiKey = GUARDIAN_API_KEY,
                fromDate = fromDate,
                query = query
            ).response.results.map { ArticleMapper.fromGuardian(it) }
        } catch (e: Exception) {
            Log.e("Lumen", "Guardian fetch failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchFromNyt(query: String): List<Article> {
        return try {
            val beginDate = getSevenDaysAgoNytFormat()
            RetrofitClient.nytApi.getRecentArticles(
                apiKey = NYT_API_KEY,
                beginDate = beginDate,
                query = query
            ).response.docs?.map { ArticleMapper.fromNyt(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("Lumen", "NYT fetch failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchFromSpiegel(): List<Article> {
        // RSS has no query parameter, so topic-filtered fetches still pull the full
        // International feed; fetchAndSaveArticles() re-tags them with the active topic.
        return spiegelRssFetcher.fetchFeed().map { ArticleMapper.fromSpiegel(it) }
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
