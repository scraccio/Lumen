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
import com.example.lumen.data.model.Article
import com.example.lumen.ml.BiasResult
import com.example.lumen.network.ArticleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.lumen.ml.BiasAnalyzer
import android.view.ViewTreeObserver
import androidx.core.view.doOnLayout

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

    private val biasAnalyzer: BiasAnalyzer by lazy { BiasAnalyzer(this) }

    // cache in memoria — solo per questa sessione, non salvato nel db
    private val bodyCache = mutableMapOf<String, String>()
    private val biasCache = mutableMapOf<String, BiasResult>()

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
        }
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
        val cached = bodyCache[article.url]
        if (cached != null) {
            tvBody.text = cached
            progressBody.visibility = View.GONE
            biasCache[article.url]?.let { animateBiasIn(it) }
            return
        }

        tvBody.text = ""
        progressBody.visibility = View.VISIBLE
        llBias.visibility = View.GONE
        llBias.alpha = 0f

        // mostra "Calculating bias..."
        tvBiasLoading.visibility = View.VISIBLE
        tvBiasLoading.alpha = 1f

        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                fetcher.fetchBody(article.url, article.title)
            }

            progressBody.visibility = View.GONE

            if (!body.isNullOrBlank()) {
                bodyCache[article.url] = body
                tvBody.text = body

                val result = withContext(Dispatchers.Default) {
                    biasAnalyzer.analyze(body)
                }
                biasCache[article.url] = result
                animateBiasIn(result)

            } else {
                tvBody.text = "Could not load article content. Tap 'Read full article' to open in browser."
                tvBiasLoading.animate().alpha(0f).setDuration(300).withEndAction {
                    tvBiasLoading.visibility = View.GONE
                }.start()
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
        biasAnalyzer.close()
    }
}