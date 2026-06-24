package com.example.lumen.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.network.ArticleFetcher
import com.example.lumen.ui.ArticleActivity
import com.example.lumen.ui.ClusterAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedFragment : Fragment() {

    private lateinit var viewModel: FeedViewModel
    private lateinit var adapter: ClusterAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var filterChipsContainer: LinearLayout
    private var lastPrefsSignature: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rv_feed)
        progressBar = view.findViewById(R.id.progress_bar)
        filterChipsContainer = view.findViewById(R.id.ll_filter_chips)

        adapter = ClusterAdapter(emptyList()) { cluster ->
            val urls = ArrayList(cluster.articles.map { it.url })
            val intent = Intent(requireContext(), ArticleActivity::class.java).apply {
                putStringArrayListExtra(ArticleActivity.EXTRA_URLS, urls)
                putExtra(ArticleActivity.EXTRA_START_INDEX, 0)
            }
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val db = LumenDatabase.getInstance(requireContext())
        val repository = NewsRepository(
            db.articleDao(),
            db.followedStoryDao(),
            db.followedStoryUpdateDao(),
            db.userPrefsDao(),
            ArticleFetcher(),
            requireContext()
        )

        viewModel = ViewModelProvider(
            this,
            FeedViewModelFactory(repository, requireContext().applicationContext)
        )[FeedViewModel::class.java]

        lifecycleScope.launch {
            viewModel.clusters.collect { clusters ->
                adapter.updateClusters(clusters)
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Build chips + load. onResume re-checks prefs so edits made in Settings
        // (e.g. a removed topic) refresh the chips without restarting the app.
        maybeRefreshFromPrefs(force = true)
    }

    override fun onResume() {
        super.onResume()
        if (::filterChipsContainer.isInitialized) maybeRefreshFromPrefs(force = false)
    }

    // Rebuilds the topic chips and reloads the feed only when the stored topics or
    // sources changed since the last build, so returning from an article doesn't reset
    // the current filter while returning from a preferences edit does.
    private fun maybeRefreshFromPrefs(force: Boolean) {
        val prefs = requireContext()
            .getSharedPreferences("user_settings", android.content.Context.MODE_PRIVATE)
        val topics = prefs.getStringSet("topics", emptySet())?.toList()?.sorted() ?: emptyList()
        val sources = prefs.getStringSet("sources", emptySet())?.toList()?.sorted() ?: emptyList()
        val signature = "$topics|$sources"
        if (!force && signature == lastPrefsSignature) return
        lastPrefsSignature = signature
        buildFilterChips(filterChipsContainer, topics)
        viewModel.loadArticles()
    }

    private fun buildFilterChips(container: LinearLayout, topics: List<String>) {
        container.removeAllViews()

        // chip "All" sempre presente
        val allTopics = listOf("All") + topics
        var selectedChip: TextView? = null

        allTopics.forEach { topic ->
            val chip = TextView(requireContext()).apply {
                text = topic
                textSize = 12f
                setPadding(32, 12, 32, 12)
                setTextColor(if (topic == "All") Color.parseColor("#0D1B2A") else Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(
                        if (topic == "All") Color.parseColor("#F5C842")
                        else Color.parseColor("#1E3A5F")
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }

                setOnClickListener {
                    // aggiorna stile chip selezionata
                    selectedChip?.apply {
                        setTextColor(Color.WHITE)
                        (background as GradientDrawable).setColor(Color.parseColor("#1E3A5F"))
                    }
                    setTextColor(Color.parseColor("#0D1B2A"))
                    (background as GradientDrawable).setColor(Color.parseColor("#F5C842"))
                    selectedChip = this

                    val filter = if (topic == "All") null else topic
                    viewModel.filterByTopic(filter)
                }
            }

            if (topic == "All") selectedChip = chip
            container.addView(chip)
        }
    }
}