package com.example.lumen.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SpiegelRssParserTest {

    private val sampleFeed = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:atom="http://www.w3.org/2005/Atom">
        <channel>
        <title>DER SPIEGEL - International</title>
        <link>https://www.spiegel.de/</link>
        <atom:link href="https://www.spiegel.de/international/index.rss" rel="self" type="application/rss+xml"/>
        <item>
        <title>The Mystery of Michele</title>
        <link>https://www.spiegel.de/international/world/the-mystery-of-michele-a-835c0c91.html#ref=rss</link>
        <description>A young woman from Germany vanished without a trace.</description>
        <content:encoded>A young woman from Germany vanished without a trace.</content:encoded>
        <enclosure type="image/jpeg" url="https://cdn.prod.www.spiegel.de/images/135a1308_w520.jpg" length="0"/>
        <guid isPermaLink="false">835c0c91</guid>
        <pubDate>Fri, 12 Jun 2026 13:20:00 +0200</pubDate>
        </item>
        <item>
        <title>A Headline Without Image</title>
        <link>https://www.spiegel.de/international/business/no-image-a-066f9f4a.html</link>
        <description>No enclosure on this one.</description>
        <guid isPermaLink="false">066f9f4a</guid>
        <pubDate>Thu, 11 Jun 2026 09:00:00 +0200</pubDate>
        </item>
        </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `parses every item in the feed`() {
        val items = SpiegelRssParser.parse(sampleFeed)
        assertEquals(2, items.size)
    }

    @Test
    fun `maps title, description and image from an item`() {
        val item = SpiegelRssParser.parse(sampleFeed).first()
        assertEquals("The Mystery of Michele", item.title)
        assertEquals("A young woman from Germany vanished without a trace.", item.description)
        assertEquals("https://cdn.prod.www.spiegel.de/images/135a1308_w520.jpg", item.imageUrl)
    }

    @Test
    fun `strips the ref=rss fragment from the article link`() {
        val item = SpiegelRssParser.parse(sampleFeed).first()
        assertEquals(
            "https://www.spiegel.de/international/world/the-mystery-of-michele-a-835c0c91.html",
            item.link
        )
    }

    @Test
    fun `leaves a link without a fragment untouched`() {
        val item = SpiegelRssParser.parse(sampleFeed)[1]
        assertEquals(
            "https://www.spiegel.de/international/business/no-image-a-066f9f4a.html",
            item.link
        )
    }

    @Test
    fun `image is null when an item has no enclosure`() {
        val item = SpiegelRssParser.parse(sampleFeed)[1]
        assertNull(item.imageUrl)
    }

    @Test
    fun `mapper produces a Der Spiegel article with the parsed publish time`() {
        val item = SpiegelRssParser.parse(sampleFeed).first()
        val article = ArticleMapper.fromSpiegel(item)

        assertEquals("Der Spiegel", article.source)
        assertEquals(item.link, article.url)
        assertEquals("The Mystery of Michele", article.title)
        // Topic derived from the URL section segment ".../international/world/...".
        assertEquals("World", article.topic)

        // RFC-822 "Fri, 12 Jun 2026 13:20:00 +0200" — independent java.time oracle.
        val expected = OffsetDateTime.of(2026, 6, 12, 13, 20, 0, 0, ZoneOffset.ofHours(2))
            .toInstant().toEpochMilli()
        assertEquals(expected, article.publishedAt)
    }
}
