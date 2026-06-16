package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.Story
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryDao {

    @Query("SELECT * FROM stories ORDER BY lastUpdatedAt DESC")
    fun getAllStories(): Flow<List<Story>>

    // non-Flow version for StoryMatcher (needs a plain list, not a stream)
    @Query("SELECT * FROM stories ORDER BY lastUpdatedAt DESC")
    suspend fun getAllStoriesSnapshot(): List<Story>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStory(story: Story)

    @Query("SELECT * FROM stories WHERE storyId = :storyId")
    fun getStoryById(storyId: String): Flow<List<Story>>

    @Query("""
        UPDATE stories 
        SET lastUpdatedAt = :lastUpdatedAt, articleCount = :newCount 
        WHERE storyId = :storyId
    """)
    suspend fun updateLastSeen(storyId: String, lastUpdatedAt: Long, newCount: Int)

    @Query("DELETE FROM stories WHERE storyId = :storyId")
    suspend fun deleteStory(storyId: String)
}