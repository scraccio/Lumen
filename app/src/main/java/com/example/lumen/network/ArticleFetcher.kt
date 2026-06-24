package com.example.lumen.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class ArticleFetcher {

    private val GUARDIAN_API_KEY = com.example.lumen.BuildConfig.GUARDIAN_API_KEY
    private val NYT_API_KEY = com.example.lumen.BuildConfig.NYT_API_KEY

    suspend fun fetchBody(url: String, title: String? = null): String? {
        return when {
            url.contains("theguardian.com") -> fetchGuardianBody(url)
            url.contains("nytimes.com") -> fetchNytBody(url)
            else -> fetchViaJsoup(url)
        }
    }

    private suspend fun fetchGuardianBody(url: String): String? {
        return fetchViaJsoup(url)
    }

    private suspend fun fetchNytBody(url: String): String? {
        return withContext(Dispatchers.IO) {
            // Primary: NYT search API by exact URL. getArticleByUrl requests
            // lead_paragraph/abstract/snippet, so when the doc is found we get real text.
            val apiBody = try {
                Log.d("Lumen", "Fetching NYT body for: $url")
                val response = RetrofitClient.nytApi.getArticleByUrl(
                    apiKey = NYT_API_KEY,
                    filterQuery = "web_url:(\"$url\")"
                )
                val article = response.response.docs?.firstOrNull()
                if (article != null) {
                    val parts = mutableListOf<String>()
                    article.lead_paragraph?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    article.abstract?.takeIf { it.isNotBlank() && it != article.lead_paragraph }?.let { parts.add(it) }
                    article.snippet?.takeIf { it.isNotBlank() && it != article.abstract }?.let { parts.add(it) }
                    parts.joinToString("\n\n").takeIf { it.isNotBlank() }
                } else null
            } catch (e: Exception) {
                Log.e("Lumen", "NYT API fetch failed: ${e.message}")
                null
            }

            // The search API frequently returns no doc for the exact URL (or only an
            // empty abstract). Fall back to scraping the page for its lede paragraphs.
            apiBody ?: run {
                Log.d("Lumen", "NYT API returned no body, falling back to Jsoup scrape")
                fetchViaJsoup(url)
            }
        }
    }

    private suspend fun fetchViaJsoup(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Connection", "keep-alive")
                    .referrer("https://www.google.com")
                    .timeout(10000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .get()

                val paragraphs = doc.select("article p, .article-body p, .content p, main p")
                if (paragraphs.isNotEmpty()) {
                    return@withContext paragraphs.joinToString("\n\n") { it.text() }
                }

                val allParagraphs = doc.select("p")
                val filtered = mutableListOf<String>()
                for (p in allParagraphs) {
                    if (p.text().length > 100) filtered.add(p.text())
                }
                filtered.joinToString("\n\n").takeIf { it.isNotEmpty() }

            } catch (e: Exception) {
                Log.e("Lumen", "Jsoup fetch failed: ${e.message}")
                null
            }
        }
    }
}