package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.Article
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    // Feed
    @Query("SELECT * FROM articles WHERE publishedAt > :since ORDER BY publishedAt DESC")
    suspend fun getArticlesSince(since: Long): List<Article>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<Article>)

    @Query("UPDATE articles SET isRead = 1, readAt = :readAt WHERE url = :url")
    suspend fun markAsRead(url: String, readAt: Long)

    // Stats
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 1 AND publishedAt > :since")
    suspend fun countReadSince(since: Long): Int

    @Query("SELECT topic, COUNT(*) as count FROM articles WHERE isRead = 1 GROUP BY topic")
    suspend fun getTopicBreakdown(): List<TopicCount>

    @Query("SELECT AVG(biasScore) FROM articles WHERE isRead = 1")
    suspend fun getAverageBias(): Float

    @Query("UPDATE articles SET biasScore = :score, biasLabel = :label WHERE url = :url")
    suspend fun updateBias(url: String, score: Float, label: String)

    @Query("UPDATE articles SET biasScore = :score, biasLabel = :label, biasLeftScore = :left, biasCenterScore = :center, biasRightScore = :right WHERE url = :url")
    suspend fun updateBiasScores(url: String, score: Float, label: String, left: Float, center: Float, right: Float)

    @Query("DELETE FROM articles WHERE publishedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM articles WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): Article?

    // Notifications — count unread articles newer than the given timestamp
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 0 AND publishedAt > :since")
    suspend fun countUnreadSince(since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(article: Article)

    @Query("SELECT * FROM articles WHERE publishedAt > :since ORDER BY publishedAt DESC")
    fun getAllArticles(since: Long): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE isRead = 1 ORDER BY readAt DESC")
    fun getReadArticles(): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE publishedAt > :since AND topic = :topic ORDER BY publishedAt DESC")
    fun getArticlesByTopic(since: Long, topic: String): Flow<List<Article>>

    @Query("UPDATE articles SET followedStoryId = :storyId WHERE url = :url")
    suspend fun setFollowedStoryId(url: String, storyId: String?)

    // Dashboard stats — all scoped to articles actually read (isRead = 1)
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 1 AND readAt > :since")
    suspend fun countReadSinceReadAt(since: Long): Int

    @Query("SELECT COUNT(DISTINCT source) FROM articles WHERE isRead = 1 AND readAt > :since")
    suspend fun countDistinctSourcesReadSince(since: Long): Int

    @Query("SELECT topic, COUNT(*) as count FROM articles WHERE isRead = 1 AND readAt > :since GROUP BY topic ORDER BY count DESC")
    suspend fun getTopicBreakdownSince(since: Long): List<TopicCount>

    @Query("SELECT AVG(biasLeftScore) as avgLeft, AVG(biasCenterScore) as avgCenter, AVG(biasRightScore) as avgRight FROM articles WHERE isRead = 1 AND biasLabel IS NOT NULL")
    suspend fun getBiasAverages(): BiasAverages

    @Query("SELECT readAt FROM articles WHERE isRead = 1 AND readAt > :since")
    suspend fun getReadTimestampsSince(since: Long): List<Long>

    @Query("SELECT source, COUNT(*) as count FROM articles WHERE isRead = 1 AND readAt > :since GROUP BY source ORDER BY count DESC LIMIT :limit")
    suspend fun getTopSourcesSince(since: Long, limit: Int): List<SourceCount>
}

// Helper data class for the topic breakdown query
data class TopicCount(val topic: String, val count: Int)

// Helper data classes for the dashboard stats queries
data class BiasAverages(val avgLeft: Float?, val avgCenter: Float?, val avgRight: Float?)
data class SourceCount(val source: String, val count: Int)