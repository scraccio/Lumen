package com.example.lumen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.fragments.DashboardFragment
import com.example.lumen.fragments.StoryFragment
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.notifications.NotificationScheduler
import com.example.lumen.ui.fragments.FeedFragment

class MainActivity : AppCompatActivity() {

    lateinit var database: LumenDatabase
    lateinit var repository: NewsRepository

    private lateinit var btnFeed: Button
    private lateinit var btnStories: Button
    private lateinit var btnStats: Button
    private lateinit var navIndicator: View

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) NotificationScheduler.enable(this)
        }

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

        maybeScheduleReminders()

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

    /** Honour the (default-on) Notifications pref: schedule reminders, requesting permission once on API 33+. */
    private fun maybeScheduleReminders() {
        val prefs = getSharedPreferences(NotificationScheduler.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(NotificationScheduler.KEY_ENABLED, true)) return
        NotificationScheduler.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationScheduler.enable(this)
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
