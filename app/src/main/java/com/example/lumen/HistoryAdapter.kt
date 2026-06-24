package com.example.lumen

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.data.model.Article
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onArticleClick: (Article) -> Unit
) : ListAdapter<Article, HistoryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Article>() {
            override fun areItemsTheSame(a: Article, b: Article) = a.url == b.url
            override fun areContentsTheSame(a: Article, b: Article) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvSource: TextView = view.findViewById(R.id.tv_history_source)
        val tvDate: TextView = view.findViewById(R.id.tv_history_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_article, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val article = getItem(position)
        holder.tvTitle.text = article.title
        holder.tvSource.text = article.source
        holder.tvSource.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(sourceColor(article.source))
        }
        holder.tvDate.text = formatDate(article.readAt ?: article.publishedAt)
        holder.itemView.setOnClickListener { onArticleClick(article) }
    }

    private fun sourceColor(source: String): Int = when (source) {
        "The Guardian" -> Color.parseColor("#005689")
        "New York Times" -> Color.parseColor("#000000")
        "Der Spiegel" -> Color.parseColor("#E64415")
        else -> Color.parseColor("#666666")
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
