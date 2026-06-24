package com.example.lumen

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.data.model.FollowedStory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoryAdapter(
    private val onStoryClick: (FollowedStory) -> Unit
) : ListAdapter<FollowedStory, StoryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FollowedStory>() {
            override fun areItemsTheSame(a: FollowedStory, b: FollowedStory) = a.id == b.id
            override fun areContentsTheSame(a: FollowedStory, b: FollowedStory) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_story_title)
        val llSources: LinearLayout = view.findViewById(R.id.ll_source_badges)
        val tvSummary: TextView = view.findViewById(R.id.tv_story_summary)
        val tvFooter: TextView = view.findViewById(R.id.tv_story_footer)
        val llTimeline: LinearLayout = view.findViewById(R.id.ll_timeline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_story_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val story = getItem(position)
        holder.tvTitle.text = story.title
        holder.tvSummary.text = story.summary

        holder.llSources.removeAllViews()
        val sources = inferSources(story.articleUrls)
        sources.forEach { source ->
            val badge = TextView(holder.itemView.context).apply {
                text = source
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(16, 4, 16, 4)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(sourceColor(source))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
            }
            holder.llSources.addView(badge)
        }

        val updateText = if (story.updateCount == 0) "No updates yet" else "${story.updateCount} update${if (story.updateCount > 1) "s" else ""}"
        val dateText = formatDate(story.lastUpdatedAt)
        holder.tvFooter.text = "$updateText · $dateText"

        holder.llTimeline.removeAllViews()
        val totalDots = maxOf(1, story.updateCount + 1)
        for (i in 0 until totalDots) {
            val alpha = when {
                i == 0 -> 255
                i == 1 -> 200
                else -> 100
            }
            val dot = View(holder.itemView.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(alpha, 245, 200, 66))
                }
                layoutParams = LinearLayout.LayoutParams(20, 20).apply { bottomMargin = 6 }
            }
            holder.llTimeline.addView(dot)

            if (i < totalDots - 1) {
                val line = View(holder.itemView.context).apply {
                    setBackgroundColor(Color.argb(60, 245, 200, 66))
                    layoutParams = LinearLayout.LayoutParams(4, 24).apply {
                        leftMargin = 8
                        bottomMargin = 6
                    }
                }
                holder.llTimeline.addView(line)
            }
        }

        holder.itemView.setOnClickListener { onStoryClick(story) }
    }

    private fun inferSources(articleUrls: String): List<String> {
        val urls = articleUrls.split(",").map { it.trim() }
        return urls.mapNotNull { url ->
            when {
                url.contains("theguardian") -> "Guardian"
                url.contains("nytimes") -> "NYT"
                else -> null
            }
        }.distinct()
    }

    private fun sourceColor(source: String): Int = when (source) {
        "Guardian" -> Color.parseColor("#005689")
        "NYT" -> Color.parseColor("#000000")
        else -> Color.parseColor("#666666")
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
