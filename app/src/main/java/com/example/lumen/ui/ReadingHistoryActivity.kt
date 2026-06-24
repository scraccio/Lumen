package com.example.lumen.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.HistoryAdapter
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.network.ArticleFetcher
import kotlinx.coroutines.launch

class ReadingHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_history)

        tvEmpty = findViewById(R.id.tv_history_empty)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        adapter = HistoryAdapter { article ->
            val intent = Intent(this, ArticleActivity::class.java).apply {
                putStringArrayListExtra(ArticleActivity.EXTRA_URLS, arrayListOf(article.url))
                putExtra(ArticleActivity.EXTRA_START_INDEX, 0)
            }
            startActivity(intent)
        }

        findViewById<RecyclerView>(R.id.rv_history).apply {
            layoutManager = LinearLayoutManager(this@ReadingHistoryActivity)
            adapter = this@ReadingHistoryActivity.adapter
        }

        val db = LumenDatabase.getInstance(this)
        val repository = NewsRepository(
            db.articleDao(),
            db.followedStoryDao(),
            db.followedStoryUpdateDao(),
            db.userPrefsDao(),
            ArticleFetcher(),
            this
        )

        lifecycleScope.launch {
            repository.getReadArticles().collect { articles ->
                adapter.submitList(articles)
                tvEmpty.visibility = if (articles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
