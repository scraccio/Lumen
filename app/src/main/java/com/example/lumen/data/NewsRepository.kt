package com.example.lumen.data

import android.util.Log
import com.example.lumen.data.dao.ArticleDao
import com.example.lumen.data.dao.StoryDao
import com.example.lumen.data.dao.UserPrefsDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.UserPrefs
import com.example.lumen.ml.BiasAnalyzer
import com.example.lumen.ml.StoryMatcher
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.network.ArticleMapper
import com.example.lumen.network.RetrofitClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class NewsRepository(
    private val articleDao: ArticleDao,
    private val storyDao: StoryDao,
    private val userPrefsDao: UserPrefsDao,
    private val articleFetcher: ArticleFetcher,
    private val biasAnalyzer: BiasAnalyzer,
    private val storyMatcher: StoryMatcher
) {
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
            val storyId = storyMatcher.matchOrCreateStory(article)
            articleDao.insert(article.copy(storyId = storyId))
        }
    }

    suspend fun markAsRead(url: String) {
        articleDao.markAsRead(url)
    }

    // Check if cache is fresh (less than 1 hour old)
    suspend fun isCacheFresh(): Boolean {
        val oneHourAgo = System.currentTimeMillis() - 3_600_000
        return articleDao.getArticlesSince(oneHourAgo).isNotEmpty()
    }

    // Prefs
    suspend fun getPrefs(): UserPrefs? = userPrefsDao.getPrefs()

    suspend fun savePrefs(prefs: UserPrefs) {
        userPrefsDao.savePrefs(prefs)
    }

    // Stats
    suspend fun getReadCountThisWeek(): Int {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 3_600_000
        return articleDao.countReadSince(sevenDaysAgo)
    }

    suspend fun getAverageBias(): Float = articleDao.getAverageBias()

    // keep metadata for 30 days, stories forever
    suspend fun pruneOldArticles() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 3_600_000
        articleDao.deleteOlderThan(thirtyDaysAgo)
        // stories are never deleted automatically
    }

    private val GUARDIAN_API_KEY = "b3c97802-79aa-4bb8-a58d-18de85ef8e8b"
    private val NYT_API_KEY = "IiLPGO7SSWdxukLhm979mxARzvkmcixjEyV7sWOEifoOI6TA"

    suspend fun fetchAndSaveArticles() {
        val fromDate = getSevenDaysAgoFormatted()

        val guardianArticles = fetchFromGuardian(fromDate)
        //Log.d("Lumen", "Guardian fetched: ${guardianArticles.size} articles")
        guardianArticles.forEach { Log.d("Lumen", "[Guardian] ${it.title}") }

        val nytArticles = fetchFromNyt()
        //Log.d("Lumen", "NYT fetched: ${nytArticles.size} articles")
        nytArticles.forEach { Log.d("Lumen", "[NYT] ${it.title}") }

        val allArticles = (guardianArticles + nytArticles)
            .distinctBy { it.url }  // rimuovi duplicati per URL prima di salvare
        //Log.d("Lumen", "Total articles to save: ${allArticles.size}")

        saveArticles(allArticles)
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
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3_600_000
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(sevenDaysAgo))
    }

    private fun getSevenDaysAgoNytFormat(): String {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3_600_000
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(sevenDaysAgo))
    }
}