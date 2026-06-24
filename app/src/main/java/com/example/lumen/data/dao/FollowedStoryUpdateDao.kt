package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.FollowedStoryUpdate
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedStoryUpdateDao {

    @Query("SELECT * FROM followed_story_updates WHERE storyId = :storyId ORDER BY createdAt DESC")
    fun getUpdatesForStory(storyId: String): Flow<List<FollowedStoryUpdate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(update: FollowedStoryUpdate)

    @Query("DELETE FROM followed_story_updates WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}
