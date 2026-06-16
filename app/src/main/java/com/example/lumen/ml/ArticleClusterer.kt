package com.example.lumen.ml

import android.content.Context
import android.util.Log
import com.example.lumen.data.model.Article

class ArticleClusterer(context: Context) {

    private val embedder = MiniLMEmbedder(context)

    fun cluster(articles: List<Article>): List<List<Article>> {
        if (articles.isEmpty()) return emptyList()

        Log.d("Lumen", "Clustering ${articles.size} articles with MiniLM...")

        val embeddings = articles.map { embedder.embed(it.title) }

        val assigned = BooleanArray(articles.size) { false }
        val clusters = mutableListOf<MutableList<Article>>()

        for (i in articles.indices) {
            if (assigned[i]) continue
            val embI = embeddings[i] ?: continue

            val cluster = mutableListOf(articles[i])
            assigned[i] = true

            for (j in i + 1 until articles.size) {
                if (assigned[j]) continue
                val embJ = embeddings[j] ?: continue

                val sim = embedder.similarity(embI, embJ)

                if (sim >= 0.5f) {  // logga tutto sopra 0.5 indipendentemente dal threshold
                    Log.d("Lumen", "sim=${"%.2f".format(sim)} | '${articles[i].title.take(35)}' vs '${articles[j].title.take(35)}'")
                }

                if (sim >= THRESHOLD) {
                    Log.d("Lumen", "✓ sim=${"%.2f".format(sim)} '${articles[i].title.take(35)}' ~ '${articles[j].title.take(35)}'")
                    cluster.add(articles[j])
                    assigned[j] = true
                }
            }

            clusters.add(cluster)
        }

        Log.d("Lumen", "Done: ${articles.size} → ${clusters.size} clusters")

        return clusters.sortedByDescending { it.maxOf { a -> a.publishedAt } }
    }

    fun close() = embedder.close()

    companion object {
        private const val THRESHOLD = 0.50f
    }
}