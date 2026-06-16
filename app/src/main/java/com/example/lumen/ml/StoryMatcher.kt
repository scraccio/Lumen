package com.example.lumen.ml

import com.example.lumen.data.dao.StoryDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.Story
import java.util.UUID

class StoryMatcher(private val storyDao: StoryDao) {

    suspend fun matchOrCreateStory(article: Article): String? {
        val existingStories = storyDao.getAllStoriesSnapshot()

        // try to match against existing stories
        for (story in existingStories) {
            val keywords = story.keywords.split(",").map { it.trim().lowercase() }
            val titleLower = article.title.lowercase()
            val matchCount = keywords.count { titleLower.contains(it) }

            if (matchCount >= 2) {
                storyDao.updateLastSeen(
                    storyId = story.storyId,
                    lastUpdatedAt = System.currentTimeMillis(),
                    newCount = story.articleCount + 1
                )
                return story.storyId
            }
        }

        // no match — only create a new story if title has enough words
        val keywords = extractKeywords(article.title)
        if (keywords.size < 2) return null

        val newStory = Story(
            storyId = UUID.randomUUID().toString(),
            title = article.title,
            keywords = keywords.joinToString(","),
            firstSeenAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            articleCount = 1
        )
        storyDao.insertStory(newStory)
        return newStory.storyId
    }

    private fun extractKeywords(title: String): List<String> {
        val stopWords = setOf(
            "the","a","an","is","in","on","at","to","of","and",
            "or","but","for","with","this","that","its","it"
        )
        return title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .split(" ")
            .filter { it.length > 3 && it !in stopWords }
            .take(4)
    }
}