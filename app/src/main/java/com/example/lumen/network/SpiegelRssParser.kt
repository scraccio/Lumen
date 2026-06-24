package com.example.lumen.network

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Parses a Der Spiegel RSS 2.0 feed into [SpiegelArticle]s.
 *
 * Kept free of any network or Android dependency so it can be unit-tested against a
 * raw XML string. [SpiegelRssFetcher] is the thin layer that supplies the live feed.
 */
object SpiegelRssParser {

    fun parse(xml: String): List<SpiegelArticle> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            // Article links carry a "#ref=rss" tracking fragment; drop it so the URL
            // (the Article primary key) matches the canonical page and dedupes cleanly.
            val link = item.selectFirst("link")?.text()?.trim()
                ?.substringBefore("#ref=rss")
                .orEmpty()
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            SpiegelArticle(
                title = title,
                link = link,
                description = item.selectFirst("description")?.text()?.trim(),
                imageUrl = item.selectFirst("enclosure")?.attr("url")?.takeIf { it.isNotBlank() },
                pubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            )
        }
    }
}
