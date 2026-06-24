package com.example.lumen.fragments

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.lumen.MainActivity
import com.example.lumen.R
import com.example.lumen.data.model.DashboardStats
import com.example.lumen.data.model.DayActivity
import com.example.lumen.data.model.SourceShare
import com.example.lumen.data.model.TopicShare
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        val root = view ?: return
        val repository = (requireActivity() as MainActivity).repository
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = repository.getStats()
            bind(root, stats)
        }
    }

    private fun bind(root: View, stats: DashboardStats) {
        root.findViewById<TextView>(R.id.tv_stat_articles).text = stats.articlesRead.toString()
        root.findViewById<TextView>(R.id.tv_stat_stories).text = stats.storiesFollowed.toString()
        root.findViewById<TextView>(R.id.tv_stat_sources).text = stats.sourcesUsed.toString()
        root.findViewById<TextView>(R.id.tv_stat_bias).text = "${stats.biasTopPct}%"
        root.findViewById<TextView>(R.id.tv_stat_bias_label).text = "Bias: ${stats.biasLabel}"

        bindTopics(root, stats.topics)
        bindBias(root, stats)
        bindActivity(root, stats.dailyActivity)
        bindTopSources(root, stats.topSources)
    }

    private fun bindTopics(root: View, topics: List<TopicShare>) {
        val container = root.findViewById<LinearLayout>(R.id.ll_topics)
        val empty = root.findViewById<TextView>(R.id.tv_topics_empty)
        container.removeAllViews()
        if (topics.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        topics.forEach { addBarRow(container, prettyTopic(it.topic), it.pct) }
    }

    private fun bindBias(root: View, stats: DashboardStats) {
        val container = root.findViewById<LinearLayout>(R.id.ll_bias)
        container.removeAllViews()
        addBarRow(container, "Left", stats.biasLeftPct)
        addBarRow(container, "Center", stats.biasCenterPct)
        addBarRow(container, "Right", stats.biasRightPct)
    }

    /** Inflates one item_stat_bar row and sizes the fill via layout weights (0..100). */
    private fun addBarRow(container: LinearLayout, label: String, pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        val row = layoutInflater.inflate(R.layout.item_stat_bar, container, false)
        row.findViewById<TextView>(R.id.tv_bar_label).text = label
        row.findViewById<TextView>(R.id.tv_bar_pct).text = "$clamped%"
        val fill = row.findViewById<View>(R.id.v_bar_fill)
        val rest = row.findViewById<View>(R.id.v_bar_rest)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = clamped.toFloat()
        (rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - clamped).toFloat()
        fill.requestLayout()
        rest.requestLayout()
        container.addView(row)
    }

    private fun bindActivity(root: View, days: List<DayActivity>) {
        val container = root.findViewById<LinearLayout>(R.id.ll_activity)
        container.removeAllViews()
        val max = days.maxOfOrNull { it.count } ?: 0
        val squareSize = dp(30)
        days.forEach { day ->
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val square = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(squareSize, squareSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(activityColor(day.count, max))
                }
            }
            val label = TextView(requireContext()).apply {
                text = day.label
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            cell.addView(square)
            cell.addView(label)
            container.addView(cell)
        }
    }

    private fun bindTopSources(root: View, sources: List<SourceShare>) {
        val container = root.findViewById<LinearLayout>(R.id.ll_top_sources)
        val empty = root.findViewById<TextView>(R.id.tv_sources_empty)
        container.removeAllViews()
        if (sources.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        sources.forEach { s ->
            val chip = TextView(requireContext()).apply {
                text = "${s.source} · ${s.count}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(40).toFloat()
                    setColor(Color.parseColor("#1E3A5F"))
                    setStroke(dp(1), Color.parseColor("#2A4A6B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
            }
            container.addView(chip)
        }
    }

    private fun activityColor(count: Int, max: Int): Int {
        if (count <= 0) return Color.parseColor("#22344F")
        val t = if (max > 0) count.toFloat() / max else 1f
        val alpha = (0.35f + 0.65f * t).coerceIn(0f, 1f)
        return Color.argb((alpha * 255).toInt(), 0xF5, 0xC8, 0x42)
    }

    private fun prettyTopic(topic: String): String =
        topic.lowercase().replaceFirstChar { it.uppercase() }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
