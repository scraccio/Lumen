package com.example.lumen

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.fragments.DashboardFragment
import com.example.lumen.fragments.StoryFragment
import com.example.lumen.ml.BiasAnalyzer
import com.example.lumen.ml.StoryMatcher
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.ui.fragments.FeedFragment

class MainActivity : AppCompatActivity() {

    lateinit var database: LumenDatabase
    lateinit var repository: NewsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.lumen_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Initialize database and repository
        database = LumenDatabase.getInstance(this)
        repository = NewsRepository(
            database.articleDao(),
            database.storyDao(),
            database.userPrefsDao(),
            ArticleFetcher(),
            BiasAnalyzer(this),
            StoryMatcher(database.storyDao())
        )

        // Load default fragment on first launch
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FeedFragment())
                .commit()
        }

        // Wire up bottom nav buttons
        findViewById<android.widget.Button>(R.id.feed).setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FeedFragment())
                .commit()
        }

        findViewById<android.widget.Button>(R.id.stories).setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, StoryFragment())
                .commit()
        }

        findViewById<android.widget.Button>(R.id.stats).setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
        }
    }
}