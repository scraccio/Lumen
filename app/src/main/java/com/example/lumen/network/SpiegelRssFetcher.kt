package com.example.lumen.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Fetches a Der Spiegel RSS feed over the network and parses it via [SpiegelRssParser].
 *
 * Der Spiegel offers no JSON search API (unlike Guardian/NYT), so the feed is its only
 * machine-readable source. The English-language "International" edition is used because
 * the rest of the app — topic chips, bias model, MiniLM clustering — is English.
 */
class SpiegelRssFetcher {

    suspend fun fetchFeed(url: String = INTERNATIONAL_FEED): List<SpiegelArticle> =
        withContext(Dispatchers.IO) {
            try {
                val xml = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .ignoreContentType(true) // feed is served as application/rss+xml
                    .maxBodySize(0)           // no truncation; feeds can exceed Jsoup's 2MB default
                    .timeout(10000)
                    .execute()
                    .body()
                SpiegelRssParser.parse(xml)
            } catch (e: Exception) {
                Log.e("Lumen", "Spiegel fetch failed: ${e.message}")
                emptyList()
            }
        }

    companion object {
        const val INTERNATIONAL_FEED = "https://www.spiegel.de/international/index.rss"
    }
}
