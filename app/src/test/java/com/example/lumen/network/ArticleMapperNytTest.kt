package com.example.lumen.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleMapperNytTest {

    private fun nytArticle(
        abstract: String? = null,
        snippet: String? = null
    ) = NytArticle(
        headline = NytHeadline("Pochettino's Selection Dilemma"),
        web_url = "https://www.nytimes.com/2026/06/25/podcasts/pochettino.html",
        abstract = abstract,
        lead_paragraph = null,
        snippet = snippet,
        pub_date = "2026-06-25T09:00:00-05:00",
        section_name = "Soccer",
        multimedia = null
    )

    @Test
    fun `fromNyt stores the abstract as the article body`() {
        // The NYT search/feed response delivers the abstract paired with the correct
        // web_url. Capturing it here is the only reliable NYT body — the by-URL API
        // lookup returns 0 docs and scraping nytimes.com is 403-blocked.
        val article = ArticleMapper.fromNyt(nytArticle(abstract = "Will the coach rest his stars?"))
        assertEquals("Will the coach rest his stars?", article.body)
    }

    @Test
    fun `fromNyt leaves body null when the abstract is blank`() {
        val article = ArticleMapper.fromNyt(nytArticle(abstract = "  "))
        assertNull(article.body)
    }
}
