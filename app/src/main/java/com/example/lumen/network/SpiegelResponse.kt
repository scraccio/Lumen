package com.example.lumen.network

/** One <item> from a Der Spiegel RSS feed, before mapping to an [com.example.lumen.data.model.Article]. */
data class SpiegelArticle(
    val title: String,
    val link: String,
    val description: String?,
    val imageUrl: String?,
    val pubDate: String
)
