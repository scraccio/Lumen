package com.example.lumen.data.model

data class ArticleCluster(
    val articles: List<Article>,
    val representativeTitle: String = articles.first().title,
    val sources: List<String> = articles.map { it.source }.distinct(),
    val latestPublishedAt: Long = articles.maxOf { it.publishedAt },
    val imageUrl: String? = articles.firstOrNull { it.imageUrl != null }?.imageUrl
)