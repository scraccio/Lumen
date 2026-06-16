package com.example.lumen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stories")
data class Story(
    @PrimaryKey val storyId: String,     // e.g. "story_eu_ai_act"
    val title: String,                   // human-readable story name
    val keywords: String,                // comma-separated key entities
    val firstSeenAt: Long,
    val lastUpdatedAt: Long,
    val articleCount: Int = 0
)