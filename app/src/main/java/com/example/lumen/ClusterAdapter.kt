package com.example.lumen.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.lumen.R
import com.example.lumen.data.model.ArticleCluster
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClusterAdapter(
    private var clusters: List<ArticleCluster>,
    private val onClusterClick: (ArticleCluster) -> Unit
) : RecyclerView.Adapter<ClusterAdapter.ClusterViewHolder>() {

    inner class ClusterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        val title: TextView = itemView.findViewById(R.id.tv_title)
        val sourcesRow: LinearLayout = itemView.findViewById(R.id.ll_sources)
        val date: TextView = itemView.findViewById(R.id.tv_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClusterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cluster, parent, false)
        return ClusterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClusterViewHolder, position: Int) {
        val cluster = clusters[position]

        holder.title.text = cluster.representativeTitle
        holder.date.text = formatDate(cluster.latestPublishedAt)

        if (cluster.imageUrl != null) {
            holder.thumbnail.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(cluster.imageUrl)
                .centerCrop()
                .into(holder.thumbnail)
        } else {
            holder.thumbnail.visibility = View.GONE
        }

        // source badges
        holder.sourcesRow.removeAllViews()
        cluster.sources.forEach { source ->
            val badge = TextView(holder.itemView.context).apply {
                text = source
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(20, 6, 20, 6)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(sourceColor(source))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
            }
            holder.sourcesRow.addView(badge)
        }

        // show count badge if clustered
        if (cluster.articles.size > 1) {
            val countBadge = TextView(holder.itemView.context).apply {
                text = "${cluster.articles.size} sources"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(8, 6, 8, 6)
            }
            holder.sourcesRow.addView(countBadge)
        }

        holder.itemView.setOnClickListener { onClusterClick(cluster) }
    }

    override fun getItemCount() = clusters.size

    fun updateClusters(newClusters: List<ArticleCluster>) {
        clusters = newClusters
        notifyDataSetChanged()
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
}