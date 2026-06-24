package com.example.lumen.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.data.model.FollowedStory
import com.example.lumen.data.model.FollowedStoryUpdate
import com.example.lumen.network.ArticleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STORY_ID = "extra_story_id"
    }

    private lateinit var tvTitle: TextView
    private lateinit var llSources: LinearLayout
    private lateinit var tvFollowedDate: TextView
    private lateinit var tvSummary: TextView
    private lateinit var llUpdatesContainer: LinearLayout
    private lateinit var llSourceArticles: LinearLayout
    private lateinit var tvNoUpdates: TextView
    private lateinit var repository: NewsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_detail)

        tvTitle = findViewById(R.id.tv_detail_title)
        llSources = findViewById(R.id.ll_detail_sources)
        tvFollowedDate = findViewById(R.id.tv_detail_followed_date)
        tvSummary = findViewById(R.id.tv_detail_summary)
        llUpdatesContainer = findViewById(R.id.ll_updates_container)
        llSourceArticles = findViewById(R.id.ll_source_articles)
        tvNoUpdates = findViewById(R.id.tv_no_updates)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val db = LumenDatabase.getInstance(this)
        repository = NewsRepository(
            db.articleDao(),
            db.followedStoryDao(),
            db.followedStoryUpdateDao(),
            db.userPrefsDao(),
            ArticleFetcher(),
            this
        )

        val storyId = intent.getStringExtra(EXTRA_STORY_ID) ?: return finish()

        findViewById<ImageButton>(R.id.btn_delete).setOnClickListener {
            lifecycleScope.launch {
                repository.unfollowStory(storyId)
                Toast.makeText(this@StoryDetailActivity, "Story deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        lifecycleScope.launch {
            val story = withContext(Dispatchers.IO) { repository.getStoryById(storyId) } ?: return@launch finish()
            bindStory(story)

            val urls = story.articleUrls.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val articles = withContext(Dispatchers.IO) { repository.getArticlesByUrls(urls) }
            renderSources(articles)

            repository.getStoryUpdates(storyId).collect { updates ->
                bindUpdates(updates)
            }
        }
    }

    private fun renderSources(articles: List<com.example.lumen.data.model.Article>) {
        llSourceArticles.removeAllViews()
        val allUrls = ArrayList(articles.map { it.url })
        articles.forEachIndexed { index, article ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_story_source, llSourceArticles, false)
            val badge = row.findViewById<TextView>(R.id.tv_source_badge)
            badge.text = article.source
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(sourceColor(article.source))
            }
            row.findViewById<TextView>(R.id.tv_source_title).text = article.title
            row.setOnClickListener {
                val intent = Intent(this, ArticleActivity::class.java).apply {
                    putStringArrayListExtra(ArticleActivity.EXTRA_URLS, allUrls)
                    putExtra(ArticleActivity.EXTRA_START_INDEX, index)
                }
                startActivity(intent)
            }
            llSourceArticles.addView(row)
        }
    }

    private fun sourceColor(source: String): Int = when (source) {
        "The Guardian" -> Color.parseColor("#005689")
        "New York Times" -> Color.parseColor("#000000")
        "Der Spiegel" -> Color.parseColor("#E64415")
        else -> Color.parseColor("#666666")
    }

    private fun bindStory(story: FollowedStory) {
        tvTitle.text = story.title
        tvSummary.text = story.summary
        tvFollowedDate.text = "Followed ${formatDate(story.createdAt)}"

        llSources.removeAllViews()
        val sources = story.articleUrls.split(",").mapNotNull { url ->
            when {
                url.contains("theguardian") -> "Guardian"
                url.contains("nytimes") -> "NYT"
                else -> null
            }
        }.distinct()
        sources.forEach { source ->
            val badge = TextView(this).apply {
                text = source
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(16, 4, 16, 4)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(if (source == "Guardian") Color.parseColor("#005689") else Color.parseColor("#000000"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
            }
            llSources.addView(badge)
        }
    }

    private fun bindUpdates(updates: List<FollowedStoryUpdate>) {
        llUpdatesContainer.removeAllViews()
        if (updates.isEmpty()) {
            tvNoUpdates.visibility = View.VISIBLE
            return
        }
        tvNoUpdates.visibility = View.GONE
        for (update in updates) {
            val updateView = LayoutInflater.from(this)
                .inflate(R.layout.item_story_update, llUpdatesContainer, false)
            updateView.findViewById<TextView>(R.id.tv_update_date).text = formatDate(update.createdAt)
            updateView.findViewById<TextView>(R.id.tv_update_text).text = update.updateText
            llUpdatesContainer.addView(updateView)
        }
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
