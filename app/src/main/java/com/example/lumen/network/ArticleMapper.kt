package com.example.lumen.network

import android.util.Log
import com.example.lumen.data.model.Article
import java.text.SimpleDateFormat
import java.util.Locale

object ArticleMapper {

    fun fromGuardian(guardianArticle: GuardianArticle): Article {
        return Article(
            url = guardianArticle.webUrl,
            title = guardianArticle.webTitle,
            source = "The Guardian",
            topic = guardianArticle.sectionName,
            publishedAt = parseGuardianDate(guardianArticle.webPublicationDate),
            imageUrl = guardianArticle.fields?.thumbnail,
            fetchedAt = System.currentTimeMillis()
        )
    }

    fun fromNyt(nytArticle: NytArticle): Article {
        val thumbnail = nytArticle.multimedia?.thumbnail?.url
            ?: nytArticle.multimedia?.default?.url

        return Article(
            url = nytArticle.web_url,
            title = nytArticle.headline.main,
            source = "New York Times",
            topic = nytArticle.section_name ?: "General",
            publishedAt = parseNytDate(nytArticle.pub_date),
            imageUrl = thumbnail,
            fetchedAt = System.currentTimeMillis()
        )
    }

    fun fromSpiegel(spiegelArticle: SpiegelArticle): Article {
        return Article(
            url = spiegelArticle.link,
            title = spiegelArticle.title,
            source = "Der Spiegel",
            topic = sectionFromUrl(spiegelArticle.link),
            publishedAt = parseSpiegelDate(spiegelArticle.pubDate),
            imageUrl = spiegelArticle.imageUrl,
            fetchedAt = System.currentTimeMillis()
        )
    }

    /** Derives a display topic from the Spiegel URL path, e.g.
     *  ".../international/world/..." → "World". Falls back to "International". */
    private fun sectionFromUrl(url: String): String {
        val segments = url.substringAfter("spiegel.de/", "")
            .substringBefore('?')
            .split('/')
            .filter { it.isNotBlank() }
        // segments[0] is the locale ("international"); segments[1] is the section.
        val section = segments.getOrNull(1)?.takeUnless { it.endsWith(".html") }
        return section?.replaceFirstChar { it.uppercase() } ?: "International"
    }

    private fun parseSpiegelDate(dateString: String): Long {
        return try {
            // RFC-822, e.g. "Fri, 12 Jun 2026 13:20:00 +0200".
            // Locale.ENGLISH is mandatory: the device's default locale would fail to
            // parse English weekday/month names ("Fri", "Jun").
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                .parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun parseGuardianDate(dateString: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                .parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun parseNytDate(dateString: String): Long {
        return try {
            // NYT format: "2026-04-30T09:00:00-05:00"
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                .parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}