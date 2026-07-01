package com.example.lumen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class Article(
    @PrimaryKey val url: String,
    val title: String,
    val source: String,
    val topic: String,
    val publishedAt: Long,
    val imageUrl: String? = null,

    // Body text captured at fetch time when the source delivers it (e.g. the NYT
    // abstract). Null for sources whose body is scraped lazily on open (Guardian, Spiegel).
    val body: String? = null,

    // Bias
    val biasScore: Float? = null,
    val biasLabel: String? = null,
    val biasLeftScore: Float? = null,    // aggiungi
    val biasCenterScore: Float? = null, // aggiungi
    val biasRightScore: Float? = null,  // aggiungi

    // Deduplication
    val clusterId: String? = null,

    // Followed story reference
    val followedStoryId: String? = null,

    // Reading state
    val isRead: Boolean = false,
    val readAt: Long? = null,

    // Cache control
    val fetchedAt: Long = System.currentTimeMillis()
)