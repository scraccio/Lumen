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
