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
