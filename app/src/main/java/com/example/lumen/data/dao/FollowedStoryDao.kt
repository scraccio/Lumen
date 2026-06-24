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
