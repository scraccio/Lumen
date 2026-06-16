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