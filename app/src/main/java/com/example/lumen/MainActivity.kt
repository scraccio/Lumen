package com.example.lumen

import android.graphics.Typeface
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.fragments.DashboardFragment
import com.example.lumen.fragments.StoryFragment
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.ui.fragments.FeedFragment

class MainActivity : AppCompatActivity() {

    lateinit var database: LumenDatabase
    lateinit var repository: NewsRepository

    private lateinit var btnFeed: Button
    private lateinit var btnStories: Button
    private lateinit var btnStats: Button
    private lateinit var navIndicator: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.lumen_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        database = LumenDatabase.getInstance(this)
        repository = NewsRepository(
            database.articleDao(),
            database.followedStoryDao(),
            database.followedStoryUpdateDao(),
            database.userPrefsDao(),
            ArticleFetcher(),
            this
        )

        btnFeed = findViewById(R.id.feed)
        btnStories = findViewById(R.id.stories)
        btnStats = findViewById(R.id.stats)
        navIndicator = findViewById(R.id.nav_indicator)

        // set indicator width = 1 tab width after layout
        btnFeed.post {
            navIndicator.layoutParams.width = btnFeed.width
            navIndicator.requestLayout()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FeedFragment())
                .commit()
            setActiveTab(0, animate = false)
        }

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        btnFeed.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FeedFragment())
                .commit()
            setActiveTab(0)
        }

        btnStories.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, StoryFragment())
                .commit()
            setActiveTab(1)
        }

        btnStats.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
            setActiveTab(2)
        }
    }

    private fun setActiveTab(index: Int, animate: Boolean = true) {
        listOf(btnFeed, btnStories, btnStats).forEachIndexed { i, btn ->
            btn.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
        }

        val targetX = (navIndicator.layoutParams.width * index).toFloat()
        if (animate) {
            navIndicator.animate()
                .translationX(targetX)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            navIndicator.translationX = targetX
        }
    }
}
