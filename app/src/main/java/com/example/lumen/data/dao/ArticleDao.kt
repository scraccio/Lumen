package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.Article
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    // Feed
    @Query("SELECT * FROM articles WHERE topic = :topic ORDER BY publishedAt DESC")
    fun getArticlesByTopic(topic: String): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE publishedAt > :since ORDER BY publishedAt DESC")
    suspend fun getArticlesSince(since: Long): List<Article>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<Article>)

    @Query("UPDATE articles SET isRead = 1 WHERE url = :url")
    suspend fun markAsRead(url: String)

    // Stories
    @Query("SELECT * FROM articles WHERE storyId = :storyId ORDER BY publishedAt ASC")
    fun getStory(storyId: String): Flow<List<Article>>

    @Query("SELECT * FROM articles GROUP BY storyId ORDER BY publishedAt DESC")
    fun getAllStories(): Flow<List<Article>>

    // Stats
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 1 AND publishedAt > :since")
    suspend fun countReadSince(since: Long): Int

    @Query("SELECT topic, COUNT(*) as count FROM articles WHERE isRead = 1 GROUP BY topic")
    suspend fun getTopicBreakdown(): List<TopicCount>

    @Query("SELECT AVG(biasScore) FROM articles WHERE isRead = 1")
    suspend fun getAverageBias(): Float

    @Query("UPDATE articles SET biasScore = :score, biasLabel = :label WHERE url = :url")
    suspend fun updateBias(url: String, score: Float, label: String)

    @Query("DELETE FROM articles WHERE publishedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM articles WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): Article?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(article: Article)

    @Query("SELECT * FROM articles WHERE publishedAt > :since ORDER BY publishedAt DESC")
    fun getAllArticles(since: Long): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE publishedAt > :since AND topic = :topic ORDER BY publishedAt DESC")
    fun getArticlesByTopic(since: Long, topic: String): Flow<List<Article>>
}

// Helper data class for the topic breakdown query
data class TopicCount(val topic: String, val count: Int)