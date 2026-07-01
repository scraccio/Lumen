package com.example.lumen.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.lumen.R
import com.example.lumen.data.LumenDatabase
import com.example.lumen.data.NewsRepository
import com.example.lumen.data.model.Article
import com.example.lumen.ml.BiasResult
import com.example.lumen.ml.MiniLMEmbedder
import com.example.lumen.ml.T5Summarizer
import com.example.lumen.network.ArticleFetcher
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.lumen.ml.BiasAnalyzer
import android.view.ViewTreeObserver
import androidx.core.view.doOnLayout
import kotlin.math.sqrt

class ArticleActivity : AppCompatActivity() {

    private lateinit var ivImage: ImageView
    private lateinit var tvSource: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvBody: TextView
    private lateinit var progressBody: ProgressBar
    private lateinit var btnBrowser: Button
    private lateinit var hsvSources: HorizontalScrollView
    private lateinit var llSourceTabs: LinearLayout

    private val fetcher = ArticleFetcher()
    private var articles = listOf<Article>()
    private var currentIndex = 0

    private lateinit var llBias: LinearLayout
    private lateinit var vBiasLeft: View
    private lateinit var vBiasCenter: View
    private lateinit var vBiasRight: View
    private lateinit var tvBiasLeftPct: TextView
    private lateinit var tvBiasCenterPct: TextView
    private lateinit var tvBiasRightPct: TextView
    private lateinit var tvBiasLabel: TextView
    private lateinit var tvBiasLoading: TextView

    private val biasAnalyzerDelegate = lazy { BiasAnalyzer(this) }
    private val biasAnalyzer: BiasAnalyzer by biasAnalyzerDelegate

    private var currentFetchJob: kotlinx.coroutines.Job? = null

    // cache in memoria — solo per questa sessione, non salvato nel db
    private val bodyCache = mutableMapOf<String, String>()
    private val biasCache = mutableMapOf<String, BiasResult>()

    // Follow FAB and related fields
    private lateinit var fabFollow: FloatingActionButton
    private lateinit var progressFollow: ProgressBar
    private val embedderDelegate = lazy { MiniLMEmbedder(this) }
    private val embedder: MiniLMEmbedder by embedderDelegate
    private val t5Delegate = lazy { T5Summarizer(this) }
    private val t5Summarizer: T5Summarizer by t5Delegate
    private var followedStoryId: String? = null
    private lateinit var repository: NewsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_article)

        ivImage = findViewById(R.id.iv_article_image)
        tvSource = findViewById(R.id.tv_article_source)
        tvTitle = findViewById(R.id.tv_article_title)
        tvDate = findViewById(R.id.tv_article_date)
        tvBody = findViewById(R.id.tv_article_body)
        progressBody = findViewById(R.id.progress_body)
        btnBrowser = findViewById(R.id.btn_open_browser)
        hsvSources = findViewById(R.id.hsv_sources)
        llSourceTabs = findViewById(R.id.ll_source_tabs)

        llBias = findViewById(R.id.ll_bias)
        vBiasLeft = findViewById(R.id.v_bias_left)
        vBiasCenter = findViewById(R.id.v_bias_center)
        vBiasRight = findViewById(R.id.v_bias_right)
        tvBiasLeftPct = findViewById(R.id.tv_bias_left_pct)
        tvBiasCenterPct = findViewById(R.id.tv_bias_center_pct)
        tvBiasRightPct = findViewById(R.id.tv_bias_right_pct)
        tvBiasLabel = findViewById(R.id.tv_bias_label)
        tvBiasLoading = findViewById(R.id.tv_bias_loading)

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

        fabFollow = findViewById(R.id.fab_follow)
        progressFollow = findViewById(R.id.progress_follow)
        fabFollow.setOnClickListener { handleFollowTap() }

        // receive article URLs from intent
        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: return
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        loadArticlesAndDisplay(urls, startIndex)
    }

    private fun loadArticlesAndDisplay(urls: List<String>, startIndex: Int) {
        lifecycleScope.launch {
            val db = LumenDatabase.getInstance(this@ArticleActivity)
            articles = withContext(Dispatchers.IO) {
                urls.mapNotNull { url -> db.articleDao().getByUrl(url) }
            }

            currentIndex = startIndex
            setupSourceTabs()
            displayArticle(currentIndex)
            checkFollowState()
        }
    }

    private fun checkFollowState() {
        lifecycleScope.launch {
            val urls = articles.map { it.url }
            followedStoryId = withContext(Dispatchers.IO) {
                repository.isClusterFollowed(urls)
            }
            updateFabAppearance()
        }
    }

    private fun updateFabAppearance() {
        if (followedStoryId != null) {
            fabFollow.setImageResource(R.drawable.ic_bookmark)
        } else {
            fabFollow.setImageResource(R.drawable.ic_bookmark_border)
        }
    }

    private fun handleFollowTap() {
        animateFabPress()
        if (followedStoryId != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { repository.unfollowStory(followedStoryId!!) }
                followedStoryId = null
                updateFabAppearance()
            }
            return
        }

        fabFollow.visibility = View.INVISIBLE
        progressFollow.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (summary, title) = withContext(Dispatchers.Default) {
                val bodies = mutableMapOf<String, String>()
                for (article in articles) {
                    val body = fetcher.fetchBody(article)
                    if (!body.isNullOrBlank()) bodies[article.url] = body
                }
                t5Summarizer.summarizeAndTitle(articles, bodies)
            }

            val embeddings = withContext(Dispatchers.Default) {
                articles.mapNotNull { embedder.embed(it.title) }
            }
            val centroid = computeCentroid(embeddings)
            // Keywords drive future article matching, so derive them from the real
            // article title, not the generated headline.
            val keywords = extractKeywords(articles.first().title)

            withContext(Dispatchers.IO) {
                followedStoryId = repository.followCluster(
                    title = title.ifBlank { articles.first().title },
                    keywords = keywords,
                    centroidEmbedding = centroid,
                    summary = summary,
                    articleUrls = articles.map { it.url }
                )
            }
            progressFollow.visibility = View.GONE
            fabFollow.visibility = View.VISIBLE
            updateFabAppearance()
        }
    }

    private fun animateFabPress() {
        fabFollow.animate()
            .scaleX(0.8f).scaleY(0.8f)
            .setDuration(100)
            .withEndAction {
                fabFollow.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }
            .start()
    }

    private fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(384)
        val sum = DoubleArray(384)
        for (emb in embeddings) {
            for (i in 0 until 384) {
                sum[i] = sum[i] + emb[i]
            }
        }
        val count = embeddings.size.toDouble()
        val centroid = FloatArray(384) { (sum[it] / count).toFloat() }
        val mag = sqrt(centroid.sumOf { (it * it).toDouble() }).toFloat()
        return if (mag == 0f) centroid else FloatArray(384) { centroid[it] / mag }
    }

    private fun extractKeywords(title: String): String {
        val stopWords = setOf("the","a","an","is","in","on","at","to","of","and","or","but","for","with","this","that","its","it")
        return title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .split(" ")
            .filter { it.length > 3 && it !in stopWords }
            .take(4)
            .joinToString(",")
    }

    private fun setupSourceTabs() {
        if (articles.size <= 1) {
            hsvSources.visibility = View.GONE
            return
        }

        hsvSources.visibility = View.VISIBLE
        llSourceTabs.removeAllViews()

        articles.forEachIndexed { index, article ->
            val tab = TextView(this).apply {
                text = article.source
                textSize = 12f
                setPadding(24, 10, 24, 10)
                setTextColor(if (index == currentIndex) Color.parseColor("#0D1B2A") else Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(
                        if (index == currentIndex) Color.parseColor("#F5C842")
                        else Color.parseColor("#1E3A5F")
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    currentIndex = index
                    setupSourceTabs()      // refresh tab styles
                    displayArticle(index)
                }
            }
            llSourceTabs.addView(tab)
        }
    }

    private fun showBiasResult(result: BiasResult) {
        llBias.visibility = View.VISIBLE

        tvBiasLabel.text = "${result.label.replaceFirstChar { it.uppercase() }} · ${(result.score * 100).toInt()}% confidence"
        tvBiasLeftPct.text = "${(result.leftScore * 100).toInt()}%"
        tvBiasCenterPct.text = "${(result.centerScore * 100).toInt()}%"
        tvBiasRightPct.text = "${(result.rightScore * 100).toInt()}%"

        // aspetta che il layout sia misurato prima di settare le larghezze
        llBias.doOnLayout {
            setBarWidth(vBiasLeft, result.leftScore)
            setBarWidth(vBiasCenter, result.centerScore)
            setBarWidth(vBiasRight, result.rightScore)
        }
    }

    private fun setBarWidth(bar: View, score: Float) {
        val parent = bar.parent as FrameLayout
        val newWidth = (parent.width * score).toInt()
        bar.layoutParams = bar.layoutParams.also { it.width = newWidth }
        bar.requestLayout()
    }

    private fun updateBiasBar(bar: View, label: TextView, score: Float) {
        val pct = (score * 100).toInt()
        label.text = "$pct%"
        val parent = bar.parent as FrameLayout
        parent.post {
            val newWidth = (parent.width * score).toInt()
            val params = bar.layoutParams
            params.width = newWidth
            bar.layoutParams = params
            bar.requestLayout()
        }
    }

    private fun displayArticle(index: Int) {
        val article = articles[index]

        lifecycleScope.launch { repository.markAsRead(article.url) }

        tvTitle.text = article.title
        tvSource.text = article.source
        tvDate.text = formatDate(article.publishedAt)

        // source badge color
        tvSource.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(sourceColor(article.source))
        }

        // thumbnail
        if (article.imageUrl != null) {
            ivImage.visibility = View.VISIBLE
            Glide.with(this).load(article.imageUrl).centerCrop().into(ivImage)
        } else {
            ivImage.visibility = View.GONE
        }

        // open in browser
        btnBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
        }

        // fetch body
        fetchBody(article)
    }

    private fun showBiasFromArticle(article: Article) {
        val result = BiasResult(
            label = article.biasLabel ?: return,
            score = article.biasScore ?: 0f,
            leftScore = article.biasLeftScore ?: 0f,
            centerScore = article.biasCenterScore ?: 0f,
            rightScore = article.biasRightScore ?: 0f
        )
        showBiasResult(result)
    }

    private fun fetchBody(article: Article) {
        val biasEnabled = getSharedPreferences("user_settings", MODE_PRIVATE)
            .getBoolean("bias_meter_enabled", true)

        val cached = bodyCache[article.url]
        if (cached != null) {
            tvBody.text = cached
            progressBody.visibility = View.GONE
            if (biasEnabled) biasCache[article.url]?.let { showBiasResult(it) }
            return
        }

        tvBody.text = ""
        progressBody.visibility = View.VISIBLE
        llBias.visibility = View.GONE
        llBias.alpha = 0f

        // mostra "Calculating bias..." solo se il bias meter è attivo
        if (biasEnabled) {
            tvBiasLoading.visibility = View.VISIBLE
            tvBiasLoading.alpha = 1f
        } else {
            tvBiasLoading.visibility = View.GONE
        }

        currentFetchJob?.cancel()
        currentFetchJob = lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                fetcher.fetchBody(article)
            }

            progressBody.visibility = View.GONE

            if (!body.isNullOrBlank()) {
                bodyCache[article.url] = body
                tvBody.text = body

                if (biasEnabled) {
                    val result = withContext(Dispatchers.Default) {
                        biasAnalyzer.analyze(body)
                    }
                    biasCache[article.url] = result
                    withContext(Dispatchers.IO) {
                        repository.saveBias(
                            article.url, result.label, result.score,
                            result.leftScore, result.centerScore, result.rightScore
                        )
                    }
                    animateBiasIn(result)
                }

            } else {
                tvBody.text = "Could not load article content. Tap 'Read full article' to open in browser."
                if (biasEnabled) {
                    tvBiasLoading.animate().alpha(0f).setDuration(300).withEndAction {
                        tvBiasLoading.visibility = View.GONE
                    }.start()
                }
            }
        }
    }

    private fun animateBiasIn(result: BiasResult) {
        // prepara il contenuto del bias prima dell'animazione
        tvBiasLabel.text = "${result.label.replaceFirstChar { it.uppercase() }} · ${(result.score * 100).toInt()}% confidence"
        tvBiasLeftPct.text = "${(result.leftScore * 100).toInt()}%"
        tvBiasCenterPct.text = "${(result.centerScore * 100).toInt()}%"
        tvBiasRightPct.text = "${(result.rightScore * 100).toInt()}%"
        llBias.visibility = View.VISIBLE

        // fade out "Calculating bias..."
        tvBiasLoading.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                tvBiasLoading.visibility = View.GONE

                // fade in + slide down del bias card
                llBias.translationY = -20f
                llBias.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(400)
                    .withEndAction {
                        // anima le barre dopo che la card è visibile
                        llBias.doOnLayout {
                            animateBiasBar(vBiasLeft, result.leftScore, 0)
                            animateBiasBar(vBiasCenter, result.centerScore, 80)
                            animateBiasBar(vBiasRight, result.rightScore, 160)
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun animateBiasBar(bar: View, score: Float, delay: Long) {
        val parent = bar.parent as FrameLayout
        val targetWidth = (parent.width * score).toInt()

        // parte da 0 e si espande
        bar.layoutParams.width = 0
        bar.requestLayout()

        bar.postDelayed({
            val animator = android.animation.ValueAnimator.ofInt(0, targetWidth).apply {
                duration = 500
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    bar.layoutParams.width = anim.animatedValue as Int
                    bar.requestLayout()
                }
            }
            animator.start()
        }, delay)
    }

    private fun sourceColor(source: String): Int = when (source) {
        "The Guardian" -> Color.parseColor("#005689")
        "New York Times" -> Color.parseColor("#000000")
        "Der Spiegel" -> Color.parseColor("#E64415")
        else -> Color.parseColor("#666666")
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
            .format(Date(timestamp))
    }

    companion object {
        const val EXTRA_URLS = "extra_urls"
        const val EXTRA_START_INDEX = "extra_start_index"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (biasAnalyzerDelegate.isInitialized()) biasAnalyzer.close()
        if (embedderDelegate.isInitialized()) embedder.close()
        if (t5Delegate.isInitialized()) t5Summarizer.close()
    }
}
